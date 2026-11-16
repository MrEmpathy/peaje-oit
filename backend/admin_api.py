"""API del panel de administración (/admin/*).

Todas las rutas exigen un JWT de una cuenta con rol ADMIN y activa. El rol se lee de la base en cada
petición (no sólo del token), así que quitarle el rol a alguien o desactivarlo tiene efecto inmediato.
Cada acción que modifica algo queda registrada en auditoria_admin, en la misma transacción del cambio.
"""
import os
import re
import secrets
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Optional

import mysql.connector
from fastapi import APIRouter, Depends, HTTPException, Query, Request
from paho.mqtt import publish
from pydantic import BaseModel, Field

from auth import obtener_usuario_actual
from common import db_cursor, iniciar_transaccion

MQTT_BROKER = os.getenv("MQTT_BROKER", "127.0.0.1")
MQTT_PORT = int(os.getenv("MQTT_PORT", 1883))
# La API usa el mismo usuario MQTT que el worker: es el único que la ACL deja publicar órdenes.
MQTT_USER = os.getenv("MQTT_WORKER_USER")
MQTT_PASSWORD = os.getenv("MQTT_WORKER_PASSWORD")
AJUSTE_MAXIMO = Decimal(os.getenv("RECARGA_MAXIMA", "100000.00"))

ESTADOS_APROBADOS = ("APROBADO", "LECTURA_DUPLICADA")
ESTADOS = (
    "APROBADO", "LECTURA_DUPLICADA", "SALDO_INSUFICIENTE", "USUARIO_NO_ENCONTRADO", "TRANSACCION_INVALIDA",
    "CREDENCIAL_INACTIVA", "METODO_DESHABILITADO", "TOKEN_INVALIDO", "TOKEN_EXPIRADO", "TOKEN_REUTILIZADO",
    "TARIFA_NO_DEFINIDA",
)
CODIGO_GARITA = re.compile(r"^[a-z0-9_]{3,50}$")
CODIGO_CARRIL = re.compile(r"^[A-Za-z0-9_-]{1,50}$")


def requiere_admin(usuario_id: int = Depends(obtener_usuario_actual)) -> dict:
    with db_cursor() as (_, cursor):
        cursor.execute(
            "SELECT id, nombre, apellido, correo, rol, activo FROM usuarios WHERE id = %s", (usuario_id,)
        )
        admin = cursor.fetchone()
    if not admin or not admin["activo"] or admin["rol"] != "ADMIN":
        raise HTTPException(status_code=403, detail="Se requiere una cuenta de administrador")
    return admin


router = APIRouter(prefix="/admin", tags=["admin"], dependencies=[Depends(requiere_admin)])


# --- Utilidades ---

def limpiar(fila: Optional[dict]) -> Optional[dict]:
    """Convierte Decimal y fechas de MySQL a tipos JSON."""
    if fila is None:
        return None
    for clave, valor in fila.items():
        if isinstance(valor, Decimal):
            fila[clave] = float(valor)
        elif isinstance(valor, datetime):
            fila[clave] = valor.strftime("%Y-%m-%d %H:%M:%S")
        elif isinstance(valor, date):
            fila[clave] = valor.isoformat()
    return fila


def limpiar_todas(filas) -> list:
    return [limpiar(f) for f in filas]


def auditar(cursor, admin: dict, request: Request, accion: str, detalle: str):
    cursor.execute(
        "INSERT INTO auditoria_admin (admin_id, accion, detalle, ip) VALUES (%s, %s, %s, %s)",
        (admin["id"], accion, detalle[:500], request.client.host if request.client else None),
    )


def buscar_garita(cursor, garita_id: int) -> dict:
    cursor.execute("SELECT id, codigo, nombre, activa FROM garitas WHERE id = %s", (garita_id,))
    garita = cursor.fetchone()
    if not garita:
        raise HTTPException(status_code=404, detail="Garita no encontrada")
    return garita


# --- Sesión ---

@router.get("/sesion")
def sesion(admin: dict = Depends(requiere_admin)):
    return {k: admin[k] for k in ("id", "nombre", "apellido", "correo", "rol")}


# --- Resumen ---

@router.get("/resumen")
def resumen():
    aprobados = ", ".join(f"'{e}'" for e in ESTADOS_APROBADOS)
    with db_cursor() as (_, cursor):
        cursor.execute("SELECT CURDATE() AS hoy")
        hoy = cursor.fetchone()["hoy"]

        cursor.execute(
            f"""
            SELECT COUNT(*) AS pasos,
                   COALESCE(SUM(estado IN ({aprobados})), 0) AS aprobados,
                   COALESCE(SUM(estado NOT IN ({aprobados})), 0) AS rechazados,
                   COALESCE(SUM(CASE WHEN estado = 'APROBADO' THEN tarifa_cobrada END), 0) AS recaudado
            FROM transacciones_peaje WHERE fecha_hora >= CURDATE()
            """
        )
        pasos_hoy = limpiar(cursor.fetchone())

        cursor.execute(
            "SELECT COUNT(*) AS cantidad, COALESCE(SUM(monto), 0) AS monto FROM recargas WHERE fecha_hora >= CURDATE()"
        )
        recargas_hoy = limpiar(cursor.fetchone())

        cursor.execute(
            """
            SELECT COUNT(*) AS total, COALESCE(SUM(activo), 0) AS activos, COALESCE(SUM(saldo), 0) AS saldo_total
            FROM usuarios
            """
        )
        usuarios = limpiar(cursor.fetchone())

        cursor.execute(
            """
            SELECT DATE(fecha_hora) AS dia, COUNT(*) AS pasos,
                   COALESCE(SUM(CASE WHEN estado = 'APROBADO' THEN tarifa_cobrada END), 0) AS recaudado
            FROM transacciones_peaje
            WHERE fecha_hora >= CURDATE() - INTERVAL 6 DAY
            GROUP BY DATE(fecha_hora)
            """
        )
        por_dia = {f["dia"]: limpiar(f) for f in cursor.fetchall()}

        cursor.execute(
            f"""
            SELECT estado, COUNT(*) AS cantidad FROM transacciones_peaje
            WHERE fecha_hora >= CURDATE() - INTERVAL 6 DAY AND estado NOT IN ({aprobados})
            GROUP BY estado ORDER BY cantidad DESC
            """
        )
        rechazos = cursor.fetchall()

        cursor.execute(
            """
            SELECT g.codigo AS garita, g.nombre, g.activa,
                   COUNT(t.id) AS pasos,
                   COALESCE(SUM(CASE WHEN t.estado = 'APROBADO' THEN t.tarifa_cobrada END), 0) AS recaudado,
                   MAX(t.fecha_hora) AS ultimo_paso
            FROM garitas g
            LEFT JOIN transacciones_peaje t ON t.garita = g.codigo AND t.fecha_hora >= CURDATE()
            GROUP BY g.id ORDER BY g.codigo
            """
        )
        garitas = limpiar_todas(cursor.fetchall())

        cursor.execute(
            """
            SELECT t.id, t.fecha_hora, t.garita, t.carril, t.metodo, t.patente, t.tipo_vehiculo,
                   t.tarifa_cobrada, t.estado, u.id AS usuario_id,
                   CONCAT(u.nombre, ' ', u.apellido) AS usuario
            FROM transacciones_peaje t LEFT JOIN usuarios u ON u.id = t.usuario_id
            ORDER BY t.fecha_hora DESC, t.id DESC LIMIT 8
            """
        )
        ultimas = limpiar_todas(cursor.fetchall())

    serie = []
    for i in range(6, -1, -1):
        dia = hoy - timedelta(days=i)
        fila = por_dia.get(dia, {"pasos": 0, "recaudado": 0.0})
        serie.append({"dia": dia.isoformat(), "pasos": fila["pasos"], "recaudado": fila["recaudado"]})

    return {
        "hoy": hoy.isoformat(),
        "pasos_hoy": pasos_hoy,
        "recargas_hoy": recargas_hoy,
        "usuarios": usuarios,
        "ultimos_7_dias": serie,
        "rechazos_7_dias": rechazos,
        "garitas": garitas,
        "ultimas_transacciones": ultimas,
    }


# --- Transacciones ---

@router.get("/transacciones")
def listar_transacciones(
    garita: Optional[str] = Query(None, max_length=50),
    estado: Optional[str] = Query(None, max_length=30),
    q: Optional[str] = Query(None, max_length=100, description="Patente, correo o nombre"),
    usuario_id: Optional[int] = Query(None, ge=1),
    desde: Optional[date] = None,
    hasta: Optional[date] = None,
    limite: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
):
    condiciones, params = [], []
    if garita:
        condiciones.append("t.garita = %s")
        params.append(garita)
    if estado == "RECHAZADO":
        condiciones.append("t.estado NOT IN (%s, %s)")
        params.extend(ESTADOS_APROBADOS)
    elif estado:
        if estado not in ESTADOS:
            raise HTTPException(status_code=400, detail="Estado desconocido")
        condiciones.append("t.estado = %s")
        params.append(estado)
    if q:
        patron = f"%{q.strip()}%"
        condiciones.append("(t.patente LIKE %s OR u.correo LIKE %s OR CONCAT(u.nombre, ' ', u.apellido) LIKE %s)")
        params.extend([patron, patron, patron])
    if usuario_id:
        condiciones.append("t.usuario_id = %s")
        params.append(usuario_id)
    if desde:
        condiciones.append("t.fecha_hora >= %s")
        params.append(desde)
    if hasta:
        condiciones.append("t.fecha_hora < %s")
        params.append(hasta + timedelta(days=1))
    donde = ("WHERE " + " AND ".join(condiciones)) if condiciones else ""

    with db_cursor() as (_, cursor):
        cursor.execute(
            f"SELECT COUNT(*) AS total FROM transacciones_peaje t LEFT JOIN usuarios u ON u.id = t.usuario_id {donde}",
            params,
        )
        total = cursor.fetchone()["total"]
        cursor.execute(
            f"""
            SELECT t.id, t.fecha_hora, t.garita, t.carril, t.metodo, t.uid_detectado, t.patente, t.tipo_vehiculo,
                   t.saldo, t.tarifa_cobrada, t.saldo_restante, t.estado,
                   u.id AS usuario_id, CONCAT(u.nombre, ' ', u.apellido) AS usuario, u.correo
            FROM transacciones_peaje t LEFT JOIN usuarios u ON u.id = t.usuario_id
            {donde}
            ORDER BY t.fecha_hora DESC, t.id DESC
            LIMIT %s OFFSET %s
            """,
            params + [limite, offset],
        )
        filas = limpiar_todas(cursor.fetchall())
    return {"total": total, "limite": limite, "offset": offset, "transacciones": filas, "estados": ESTADOS}


# --- Usuarios ---

@router.get("/usuarios")
def listar_usuarios(
    q: Optional[str] = Query(None, max_length=100),
    limite: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
):
    donde, params = "", []
    if q:
        patron = f"%{q.strip()}%"
        donde = "WHERE u.correo LIKE %s OR u.patente LIKE %s OR CONCAT(u.nombre, ' ', u.apellido) LIKE %s"
        params = [patron, patron, patron]
    with db_cursor() as (_, cursor):
        cursor.execute(f"SELECT COUNT(*) AS total FROM usuarios u {donde}", params)
        total = cursor.fetchone()["total"]
        cursor.execute(
            f"""
            SELECT u.id, u.nombre, u.apellido, u.correo, u.patente, tv.nombre AS tipo_vehiculo,
                   u.saldo, u.activo, u.rol, u.creado_en
            FROM usuarios u JOIN tipos_vehiculo tv ON tv.id = u.tipo_vehiculo_id
            {donde}
            ORDER BY u.id DESC LIMIT %s OFFSET %s
            """,
            params + [limite, offset],
        )
        filas = limpiar_todas(cursor.fetchall())
    for f in filas:
        f["activo"] = bool(f["activo"])
    return {"total": total, "limite": limite, "offset": offset, "usuarios": filas}


@router.get("/usuarios/{usuario_id}")
def detalle_usuario(usuario_id: int):
    with db_cursor() as (_, cursor):
        cursor.execute(
            """
            SELECT u.id, u.nombre, u.apellido, u.correo, u.patente, tv.codigo AS tipo_vehiculo,
                   tv.nombre AS tipo_vehiculo_nombre, u.saldo, u.activo, u.rol, u.creado_en
            FROM usuarios u JOIN tipos_vehiculo tv ON tv.id = u.tipo_vehiculo_id WHERE u.id = %s
            """,
            (usuario_id,),
        )
        usuario = limpiar(cursor.fetchone())
        if not usuario:
            raise HTTPException(status_code=404, detail="Usuario no encontrado")
        usuario["activo"] = bool(usuario["activo"])

        cursor.execute(
            "SELECT id, tipo, uid, alias, activa, creada_en FROM credenciales WHERE usuario_id = %s ORDER BY id",
            (usuario_id,),
        )
        credenciales = limpiar_todas(cursor.fetchall())
        for c in credenciales:
            c["activa"] = bool(c["activa"])

        cursor.execute(
            """
            SELECT id, fecha_hora, garita, carril, metodo, patente, tarifa_cobrada, saldo_restante, estado
            FROM transacciones_peaje WHERE usuario_id = %s ORDER BY fecha_hora DESC, id DESC LIMIT 15
            """,
            (usuario_id,),
        )
        pasos = limpiar_todas(cursor.fetchall())

        # Recargas (desde la app) y ajustes (desde el panel) en una sola lista de movimientos
        cursor.execute(
            """
            SELECT 'RECARGA' AS tipo, monto, saldo_anterior, saldo_nuevo, NULL AS motivo, NULL AS admin, fecha_hora
            FROM recargas WHERE usuario_id = %s
            UNION ALL
            SELECT 'AJUSTE', a.monto, a.saldo_anterior, a.saldo_nuevo, a.motivo, ad.correo, a.fecha_hora
            FROM ajustes_saldo a LEFT JOIN usuarios ad ON ad.id = a.admin_id WHERE a.usuario_id = %s
            ORDER BY fecha_hora DESC LIMIT 15
            """,
            (usuario_id, usuario_id),
        )
        movimientos = limpiar_todas(cursor.fetchall())

    return {"usuario": usuario, "credenciales": credenciales, "pasos": pasos, "movimientos": movimientos}


class EstadoRequest(BaseModel):
    activo: bool


@router.put("/usuarios/{usuario_id}/estado")
def cambiar_estado_usuario(usuario_id: int, datos: EstadoRequest, request: Request,
                           admin: dict = Depends(requiere_admin)):
    if usuario_id == admin["id"] and not datos.activo:
        raise HTTPException(status_code=400, detail="No puedes desactivar tu propia cuenta")
    with db_cursor() as (conn, cursor):
        cursor.execute("SELECT correo FROM usuarios WHERE id = %s", (usuario_id,))
        usuario = cursor.fetchone()
        if not usuario:
            raise HTTPException(status_code=404, detail="Usuario no encontrado")
        cursor.execute("UPDATE usuarios SET activo = %s WHERE id = %s", (int(datos.activo), usuario_id))
        auditar(cursor, admin, request, "ACTIVAR_USUARIO" if datos.activo else "DESACTIVAR_USUARIO",
                f"Usuario #{usuario_id} ({usuario['correo']})")
        conn.commit()
    return {"mensaje": "Cuenta activada" if datos.activo else "Cuenta desactivada", "activo": datos.activo}


class AjusteRequest(BaseModel):
    monto: Decimal = Field(max_digits=12, decimal_places=2, description="Positivo suma, negativo descuenta")
    motivo: str = Field(min_length=5, max_length=200)


@router.post("/usuarios/{usuario_id}/ajuste-saldo")
def ajustar_saldo(usuario_id: int, datos: AjusteRequest, request: Request, admin: dict = Depends(requiere_admin)):
    if datos.monto == 0:
        raise HTTPException(status_code=400, detail="El monto no puede ser cero")
    if abs(datos.monto) > AJUSTE_MAXIMO:
        raise HTTPException(status_code=400, detail=f"El ajuste máximo es {AJUSTE_MAXIMO}")
    motivo = datos.motivo.strip()

    with db_cursor() as (conn, cursor):
        iniciar_transaccion(conn)
        # FOR UPDATE: un cobro en la barrera al mismo tiempo espera a que termine el ajuste
        cursor.execute("SELECT correo, saldo FROM usuarios WHERE id = %s FOR UPDATE", (usuario_id,))
        usuario = cursor.fetchone()
        if not usuario:
            raise HTTPException(status_code=404, detail="Usuario no encontrado")
        saldo_anterior = usuario["saldo"]
        saldo_nuevo = saldo_anterior + datos.monto
        if saldo_nuevo < 0:
            raise HTTPException(status_code=400, detail="El saldo no puede quedar negativo")

        cursor.execute("UPDATE usuarios SET saldo = %s WHERE id = %s", (saldo_nuevo, usuario_id))
        cursor.execute(
            """
            INSERT INTO ajustes_saldo (usuario_id, admin_id, monto, saldo_anterior, saldo_nuevo, motivo)
            VALUES (%s, %s, %s, %s, %s, %s)
            """,
            (usuario_id, admin["id"], datos.monto, saldo_anterior, saldo_nuevo, motivo),
        )
        auditar(cursor, admin, request, "AJUSTAR_SALDO",
                f"Usuario #{usuario_id} ({usuario['correo']}): {datos.monto:+} ({saldo_anterior} -> {saldo_nuevo}). "
                f"Motivo: {motivo}")
        conn.commit()
    return {"mensaje": "Saldo ajustado", "saldo_anterior": float(saldo_anterior), "saldo_nuevo": float(saldo_nuevo)}


class CredencialEstadoRequest(BaseModel):
    activa: bool


@router.put("/credenciales/{credencial_id}/estado")
def cambiar_estado_credencial(credencial_id: int, datos: CredencialEstadoRequest, request: Request,
                              admin: dict = Depends(requiere_admin)):
    with db_cursor() as (conn, cursor):
        cursor.execute(
            "SELECT c.uid, c.tipo, c.usuario_id, u.correo FROM credenciales c "
            "JOIN usuarios u ON u.id = c.usuario_id WHERE c.id = %s",
            (credencial_id,),
        )
        cred = cursor.fetchone()
        if not cred:
            raise HTTPException(status_code=404, detail="Credencial no encontrada")
        cursor.execute("UPDATE credenciales SET activa = %s WHERE id = %s", (int(datos.activa), credencial_id))
        auditar(cursor, admin, request, "DESBLOQUEAR_CREDENCIAL" if datos.activa else "BLOQUEAR_CREDENCIAL",
                f"{cred['tipo']} {cred['uid']} de usuario #{cred['usuario_id']} ({cred['correo']})")
        conn.commit()
    return {"mensaje": "Credencial activada" if datos.activa else "Credencial bloqueada", "activa": datos.activa}


# --- Garitas y tarifas ---

@router.get("/garitas")
def listar_garitas():
    with db_cursor() as (_, cursor):
        cursor.execute("SELECT id, codigo, nombre, activa, creada_en FROM garitas ORDER BY codigo")
        garitas = limpiar_todas(cursor.fetchall())
        cursor.execute("SELECT id, codigo, nombre FROM tipos_vehiculo WHERE activo = 1 ORDER BY orden, id")
        tipos = cursor.fetchall()
        cursor.execute(
            "SELECT t.garita_id, tv.codigo AS tipo_vehiculo, t.monto, t.actualizada_en "
            "FROM tarifas t JOIN tipos_vehiculo tv ON tv.id = t.tipo_vehiculo_id"
        )
        tarifas = limpiar_todas(cursor.fetchall())
        cursor.execute("SELECT DISTINCT garita, carril FROM transacciones_peaje ORDER BY garita, carril")
        carriles = cursor.fetchall()

    for g in garitas:
        g["activa"] = bool(g["activa"])
        propias = {t["tipo_vehiculo"]: t for t in tarifas if t["garita_id"] == g["id"]}
        g["tarifas"] = [
            {
                "tipo_vehiculo": tv["codigo"],
                "tipo_vehiculo_nombre": tv["nombre"],
                "monto": propias[tv["codigo"]]["monto"] if tv["codigo"] in propias else None,
                "actualizada_en": propias[tv["codigo"]]["actualizada_en"] if tv["codigo"] in propias else None,
            }
            for tv in tipos
        ]
        g["carriles"] = [c["carril"] for c in carriles if c["garita"] == g["codigo"]]
    return {"garitas": garitas}


class GaritaRequest(BaseModel):
    codigo: str = Field(min_length=3, max_length=50)
    nombre: str = Field(min_length=1, max_length=100)


@router.post("/garitas", status_code=201)
def crear_garita(datos: GaritaRequest, request: Request, admin: dict = Depends(requiere_admin)):
    codigo = datos.codigo.strip().lower()
    if not CODIGO_GARITA.match(codigo):
        raise HTTPException(status_code=400,
                            detail="Código inválido: 3 a 50 letras minúsculas, números o _ (ej. garita_sur)")
    with db_cursor() as (conn, cursor):
        try:
            cursor.execute("INSERT INTO garitas (codigo, nombre) VALUES (%s, %s)", (codigo, datos.nombre.strip()))
        except mysql.connector.IntegrityError:
            raise HTTPException(status_code=409, detail="Ya existe una garita con ese código")
        nuevo_id = cursor.lastrowid
        auditar(cursor, admin, request, "CREAR_GARITA", f"{codigo} ({datos.nombre.strip()})")
        conn.commit()
    return {"mensaje": "Garita creada", "id": nuevo_id, "codigo": codigo}


class GaritaCambioRequest(BaseModel):
    nombre: Optional[str] = Field(default=None, min_length=1, max_length=100)
    activa: Optional[bool] = None


@router.put("/garitas/{garita_id}")
def modificar_garita(garita_id: int, datos: GaritaCambioRequest, request: Request,
                     admin: dict = Depends(requiere_admin)):
    if datos.nombre is None and datos.activa is None:
        raise HTTPException(status_code=400, detail="Nada que cambiar")
    with db_cursor() as (conn, cursor):
        garita = buscar_garita(cursor, garita_id)
        cambios = []
        if datos.nombre is not None:
            cursor.execute("UPDATE garitas SET nombre = %s WHERE id = %s", (datos.nombre.strip(), garita_id))
            cambios.append(f"nombre '{garita['nombre']}' -> '{datos.nombre.strip()}'")
        if datos.activa is not None:
            cursor.execute("UPDATE garitas SET activa = %s WHERE id = %s", (int(datos.activa), garita_id))
            cambios.append("activada" if datos.activa else "desactivada")
        auditar(cursor, admin, request, "MODIFICAR_GARITA", f"{garita['codigo']}: " + ", ".join(cambios))
        conn.commit()
    return {"mensaje": "Garita actualizada"}


class TarifaRequest(BaseModel):
    garita_id: int = Field(ge=1)
    tipo_vehiculo: str = Field(min_length=1, max_length=20)
    monto: Decimal = Field(ge=0, le=1_000_000, max_digits=10, decimal_places=2)


@router.put("/tarifas")
def guardar_tarifa(datos: TarifaRequest, request: Request, admin: dict = Depends(requiere_admin)):
    with db_cursor() as (conn, cursor):
        garita = buscar_garita(cursor, datos.garita_id)
        cursor.execute("SELECT id FROM tipos_vehiculo WHERE codigo = %s", (datos.tipo_vehiculo.upper(),))
        tipo = cursor.fetchone()
        if not tipo:
            raise HTTPException(status_code=400, detail="Tipo de vehículo desconocido")
        cursor.execute(
            "SELECT monto FROM tarifas WHERE garita_id = %s AND tipo_vehiculo_id = %s", (garita["id"], tipo["id"])
        )
        anterior = cursor.fetchone()
        cursor.execute(
            """
            INSERT INTO tarifas (garita_id, tipo_vehiculo_id, monto) VALUES (%s, %s, %s)
            ON DUPLICATE KEY UPDATE monto = VALUES(monto)
            """,
            (garita["id"], tipo["id"], datos.monto),
        )
        antes = anterior["monto"] if anterior else "sin tarifa"
        auditar(cursor, admin, request, "CAMBIAR_TARIFA",
                f"{garita['codigo']} / {datos.tipo_vehiculo.upper()}: {antes} -> {datos.monto}")
        conn.commit()
    return {"mensaje": "Tarifa guardada", "monto": float(datos.monto)}


@router.delete("/tarifas/{garita_id}/{tipo_vehiculo}")
def eliminar_tarifa(garita_id: int, tipo_vehiculo: str, request: Request, admin: dict = Depends(requiere_admin)):
    with db_cursor() as (conn, cursor):
        garita = buscar_garita(cursor, garita_id)
        cursor.execute(
            "DELETE t FROM tarifas t JOIN tipos_vehiculo tv ON tv.id = t.tipo_vehiculo_id "
            "WHERE t.garita_id = %s AND tv.codigo = %s",
            (garita_id, tipo_vehiculo.upper()),
        )
        if cursor.rowcount == 0:
            raise HTTPException(status_code=404, detail="Esa tarifa no existe")
        auditar(cursor, admin, request, "ELIMINAR_TARIFA", f"{garita['codigo']} / {tipo_vehiculo.upper()}")
        conn.commit()
    return {"mensaje": "Tarifa eliminada: ese tipo de vehículo será rechazado (TARIFA_NO_DEFINIDA)"}


# --- Barrera ---

class AbrirBarreraRequest(BaseModel):
    garita_id: int = Field(ge=1)
    carril: str = Field(min_length=1, max_length=50)
    motivo: str = Field(min_length=5, max_length=200)


@router.post("/barrera/abrir")
def abrir_barrera(datos: AbrirBarreraRequest, request: Request, admin: dict = Depends(requiere_admin)):
    """Apertura manual (ej. falla del lector o vehículo de emergencia). No cobra.
    El ESP32 acepta la orden en texto plano "ABRIR": abre, espera el paso del auto y cierra."""
    if not CODIGO_CARRIL.match(datos.carril):
        raise HTTPException(status_code=400, detail="Carril inválido")
    if not MQTT_USER or not MQTT_PASSWORD:
        raise HTTPException(status_code=503, detail="La API no tiene configuradas las credenciales MQTT")

    with db_cursor() as (conn, cursor):
        garita = buscar_garita(cursor, datos.garita_id)
        topic = f"peaje/{garita['codigo']}/{datos.carril}/orden"
        try:
            publish.single(
                topic, "ABRIR", qos=1, hostname=MQTT_BROKER, port=MQTT_PORT,
                client_id=f"peaje_api_admin_{secrets.token_hex(4)}",
                auth={"username": MQTT_USER, "password": MQTT_PASSWORD},
            )
        except Exception:
            raise HTTPException(status_code=503, detail="No se pudo enviar la orden al broker MQTT")
        auditar(cursor, admin, request, "ABRIR_BARRERA",
                f"{garita['codigo']}/{datos.carril}. Motivo: {datos.motivo.strip()}")
        conn.commit()
    return {"mensaje": f"Orden ABRIR enviada a {garita['nombre']}, carril {datos.carril}", "topic": topic}


# --- Auditoría ---

@router.get("/auditoria")
def listar_auditoria(limite: int = Query(50, ge=1, le=200), offset: int = Query(0, ge=0)):
    with db_cursor() as (_, cursor):
        cursor.execute("SELECT COUNT(*) AS total FROM auditoria_admin")
        total = cursor.fetchone()["total"]
        cursor.execute(
            """
            SELECT a.id, a.fecha_hora, a.accion, a.detalle, a.ip, u.correo AS admin
            FROM auditoria_admin a LEFT JOIN usuarios u ON u.id = a.admin_id
            ORDER BY a.fecha_hora DESC, a.id DESC LIMIT %s OFFSET %s
            """,
            (limite, offset),
        )
        filas = limpiar_todas(cursor.fetchall())
    return {"total": total, "limite": limite, "offset": offset, "auditoria": filas}
