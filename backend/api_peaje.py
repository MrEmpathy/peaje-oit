import os
import secrets
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Optional

import bcrypt
import mysql.connector
from fastapi import Depends, FastAPI, HTTPException, Query, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, EmailStr, Field
from slowapi import Limiter, _rate_limit_exceeded_handler
from slowapi.errors import RateLimitExceeded
from slowapi.util import get_remote_address

from auth import crear_token_jwt, obtener_usuario_actual
from common import (
    NFC_TOKEN_TTL,
    TOKEN_NFC_BYTES,
    db_cursor,
    generar_token_nfc,
    iniciar_transaccion,
    normalizar_patente,
    normalizar_uid,
)

# --- Configuración desde .env ---

RECARGA_MAXIMA = Decimal(os.getenv("RECARGA_MAXIMA", "100000.00"))
LIMITE_LOGIN = os.getenv("LIMITE_LOGIN", "5/minute")
LIMITE_REGISTRO = os.getenv("LIMITE_REGISTRO", "3/minute")
CORS_ORIGINS = [o.strip() for o in os.getenv("CORS_ORIGINS", "").split(",") if o.strip()]

limiter = Limiter(key_func=get_remote_address)


def a_float(valor):
    return float(valor) if valor is not None else None


def generar_uid_unico(cursor) -> str:
    while True:
        nuevo_uid = secrets.token_hex(4).upper()
        cursor.execute("SELECT id FROM credenciales WHERE uid = %s", (nuevo_uid,))
        if not cursor.fetchone():
            return nuevo_uid


def obtener_datos_usuario(cursor, usuario_id: int):
    cursor.execute(
        """
        SELECT u.id, u.nombre, u.apellido, u.correo, u.saldo, u.activo, u.rol, u.patente,
               tv.codigo AS tipo_vehiculo, tv.nombre AS tipo_vehiculo_nombre,
               (SELECT c.uid FROM credenciales c
                 WHERE c.usuario_id = u.id AND c.tipo = 'VIRTUAL'
                 ORDER BY c.id LIMIT 1) AS uid_nfc
        FROM usuarios u
        JOIN tipos_vehiculo tv ON tv.id = u.tipo_vehiculo_id
        WHERE u.id = %s
        """,
        (usuario_id,),
    )
    return cursor.fetchone()


def serializar_usuario(u) -> dict:
    return {
        "id": u["id"],
        "nombre": u["nombre"],
        "apellido": u["apellido"],
        "correo": u["correo"],
        "uid_nfc": u["uid_nfc"],
        "patente": u["patente"],
        "tipo_vehiculo": u["tipo_vehiculo"],
        "tipo_vehiculo_nombre": u["tipo_vehiculo_nombre"],
        "saldo": float(u["saldo"]),
        "activo": bool(u["activo"]),
        "rol": u["rol"],
    }


def validar_vehiculo(cursor, patente_raw: str, tipo_codigo: str, usuario_id: Optional[int] = None):
    """Valida patente y tipo. Devuelve (patente_normalizada, tipo_vehiculo_id) o lanza HTTPException."""
    patente = normalizar_patente(patente_raw)
    if not patente:
        raise HTTPException(
            status_code=400,
            detail="Patente inválida: usa 5 a 8 letras y números (ej. BCDF12, AB1234)",
        )
    cursor.execute(
        "SELECT id FROM tipos_vehiculo WHERE codigo = %s AND activo = 1",
        (tipo_codigo.strip().upper(),),
    )
    tipo = cursor.fetchone()
    if not tipo:
        raise HTTPException(status_code=400, detail="Tipo de vehículo inválido (ver GET /tipos-vehiculo)")

    cursor.execute("SELECT id FROM usuarios WHERE patente = %s", (patente,))
    duenio = cursor.fetchone()
    if duenio and duenio["id"] != usuario_id:
        raise HTTPException(status_code=400, detail="La patente ya está registrada en otra cuenta")
    return patente, tipo["id"]


# --- Inicialización FastAPI ---

app = FastAPI(title="API Peaje Inteligente IoT (JWT)", version="4.0")
app.state.limiter = limiter
app.add_exception_handler(RateLimitExceeded, _rate_limit_exceeded_handler)

# --- Panel web de administración ---
# API en /admin/* (sólo cuentas con rol ADMIN) y la página en /panel/ (archivos estáticos).
from admin_api import router as admin_router  # noqa: E402

app.include_router(admin_router)
PANEL_DIR = Path(__file__).resolve().parent / "panel"
app.mount("/panel", StaticFiles(directory=PANEL_DIR, html=True), name="panel")

CABECERAS_PANEL = {
    # Sólo se cargan scripts, estilos e imágenes del propio servidor: un texto malicioso guardado
    # en la base (ej. un nombre de usuario) no puede ejecutar código en el navegador del admin.
    "Content-Security-Policy": (
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"
    ),
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "no-referrer",
}


@app.middleware("http")
async def cabeceras_seguridad(request: Request, call_next):
    respuesta = await call_next(request)
    ruta = request.url.path
    if ruta.startswith("/panel"):
        respuesta.headers.update(CABECERAS_PANEL)
    if ruta.startswith("/admin"):
        respuesta.headers["Cache-Control"] = "no-store"
    return respuesta


@app.get("/", include_in_schema=False)
def inicio():
    return RedirectResponse("/panel/")

# Una app Android nativa no necesita CORS; sólo se habilita si se configura CORS_ORIGINS.
if CORS_ORIGINS:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=CORS_ORIGINS,
        allow_credentials=False,
        allow_methods=["GET", "POST", "PUT", "DELETE"],
        allow_headers=["Authorization", "Content-Type"],
    )

# --- Modelos de Entrada ---

class LoginRequest(BaseModel):
    correo: EmailStr
    password: str = Field(min_length=1, max_length=72)


class RegistroRequest(BaseModel):
    nombre: str = Field(min_length=1, max_length=80)
    apellido: str = Field(min_length=1, max_length=80)
    correo: EmailStr
    password: str = Field(min_length=8, max_length=72)  # bcrypt sólo usa 72 bytes
    patente: str = Field(min_length=5, max_length=12)
    tipo_vehiculo: str = Field(min_length=1, max_length=20, description="Código de GET /tipos-vehiculo, ej. AUTO")


class VehiculoRequest(BaseModel):
    patente: str = Field(min_length=5, max_length=12)
    tipo_vehiculo: str = Field(min_length=1, max_length=20)


class RecargaRequest(BaseModel):
    monto: Decimal = Field(gt=0, max_digits=12, decimal_places=2)


class TarjetaRequest(BaseModel):
    uid: str = Field(min_length=8, max_length=40, description="UID de la tarjeta en hexadecimal (4, 7 o 10 bytes)")
    alias: Optional[str] = Field(default=None, max_length=50)


# --- Endpoints Públicos ---

@app.get("/salud")
def salud():
    with db_cursor() as (_, cursor):
        cursor.execute("SELECT 1 AS ok")
        cursor.fetchone()
    return {"estado": "ok"}


@app.get("/tipos-vehiculo")
def listar_tipos_vehiculo():
    """Lista para el selector de la pantalla de registro."""
    with db_cursor() as (_, cursor):
        cursor.execute(
            "SELECT codigo, nombre, descripcion FROM tipos_vehiculo WHERE activo = 1 ORDER BY orden, id"
        )
        return {"tipos_vehiculo": cursor.fetchall()}


@app.get("/tarifas")
def listar_tarifas(garita: Optional[str] = Query(None, max_length=50)):
    """Tarifas vigentes por garita y tipo de vehículo."""
    with db_cursor() as (_, cursor):
        filtro, params = ("AND g.codigo = %s", (garita,)) if garita else ("", ())
        cursor.execute(
            f"""
            SELECT g.codigo AS garita, g.nombre AS garita_nombre,
                   tv.codigo AS tipo_vehiculo, tv.nombre AS tipo_vehiculo_nombre, t.monto
            FROM tarifas t
            JOIN garitas g ON g.id = t.garita_id AND g.activa = 1
            JOIN tipos_vehiculo tv ON tv.id = t.tipo_vehiculo_id AND tv.activo = 1
            WHERE 1 = 1 {filtro}
            ORDER BY g.codigo, tv.orden, tv.id
            """,
            params,
        )
        filas = cursor.fetchall()

    garitas = {}
    for f in filas:
        g = garitas.setdefault(f["garita"], {"garita": f["garita"], "nombre": f["garita_nombre"], "tarifas": []})
        g["tarifas"].append({
            "tipo_vehiculo": f["tipo_vehiculo"],
            "tipo_vehiculo_nombre": f["tipo_vehiculo_nombre"],
            "monto": float(f["monto"]),
        })
    return {"garitas": list(garitas.values())}


@app.post("/registro", status_code=status.HTTP_201_CREATED)
@limiter.limit(LIMITE_REGISTRO)
def registrar_usuario(request: Request, datos: RegistroRequest):
    correo_limpio = datos.correo.strip().lower()

    with db_cursor() as (conn, cursor):
        cursor.execute("SELECT id FROM usuarios WHERE correo = %s", (correo_limpio,))
        if cursor.fetchone():
            raise HTTPException(status_code=400, detail="El correo electrónico ya está registrado")

        patente, tipo_vehiculo_id = validar_vehiculo(cursor, datos.patente, datos.tipo_vehiculo)

        hashed_pw = bcrypt.hashpw(datos.password.encode("utf-8"), bcrypt.gensalt()).decode("utf-8")
        try:
            cursor.execute(
                """
                INSERT INTO usuarios (nombre, apellido, correo, password_hash, patente, tipo_vehiculo_id, saldo, activo)
                VALUES (%s, %s, %s, %s, %s, %s, 0.00, 1)
                """,
                (datos.nombre.strip(), datos.apellido.strip(), correo_limpio, hashed_pw, patente, tipo_vehiculo_id),
            )
            nuevo_id = cursor.lastrowid
            # UID virtual: opción de cobro por UID (modo compatible con lector RC522)
            uid_generado = generar_uid_unico(cursor)
            cursor.execute(
                "INSERT INTO credenciales (usuario_id, tipo, uid, alias) VALUES (%s, 'VIRTUAL', %s, 'UID virtual')",
                (nuevo_id, uid_generado),
            )
            conn.commit()
        except mysql.connector.IntegrityError:
            raise HTTPException(status_code=400, detail="El correo o la patente ya están registrados")

        return {
            "mensaje": "Usuario registrado exitosamente",
            "access_token": crear_token_jwt(nuevo_id, correo_limpio),
            "token_type": "bearer",
            "usuario": serializar_usuario(obtener_datos_usuario(cursor, nuevo_id)),
        }


@app.post("/login")
@limiter.limit(LIMITE_LOGIN)
def login(request: Request, credenciales: LoginRequest):
    correo_limpio = credenciales.correo.strip().lower()

    with db_cursor() as (_, cursor):
        cursor.execute("SELECT id, password_hash FROM usuarios WHERE correo = %s", (correo_limpio,))
        fila = cursor.fetchone()
        if not fila or not bcrypt.checkpw(credenciales.password.encode("utf-8"), fila["password_hash"].encode("utf-8")):
            raise HTTPException(status_code=401, detail="Correo o contraseña incorrectos")

        usuario = obtener_datos_usuario(cursor, fila["id"])
        if not usuario["activo"]:
            raise HTTPException(status_code=403, detail="La cuenta de usuario está inactiva")

        return {
            "mensaje": "Login exitoso",
            "access_token": crear_token_jwt(usuario["id"], usuario["correo"], usuario["rol"]),
            "token_type": "bearer",
            "usuario": serializar_usuario(usuario),
        }


# --- Endpoints Protegidos (Requieren Header: Authorization: Bearer <token>) ---

@app.get("/usuario/saldo")
def consultar_saldo(usuario_id: int = Depends(obtener_usuario_actual)):
    with db_cursor() as (_, cursor):
        usuario = obtener_datos_usuario(cursor, usuario_id)
        if not usuario:
            raise HTTPException(status_code=404, detail="Usuario no encontrado")
        datos = serializar_usuario(usuario)
        datos["usuario_id"] = datos.pop("id")
        return datos


@app.put("/usuario/vehiculo")
def actualizar_vehiculo(datos: VehiculoRequest, usuario_id: int = Depends(obtener_usuario_actual)):
    """Cambia la patente y/o el tipo de vehículo (ej. cambio de auto). Aplica desde el siguiente paso."""
    with db_cursor() as (conn, cursor):
        patente, tipo_vehiculo_id = validar_vehiculo(cursor, datos.patente, datos.tipo_vehiculo, usuario_id)
        try:
            cursor.execute(
                "UPDATE usuarios SET patente = %s, tipo_vehiculo_id = %s WHERE id = %s",
                (patente, tipo_vehiculo_id, usuario_id),
            )
            conn.commit()
        except mysql.connector.IntegrityError:
            raise HTTPException(status_code=400, detail="La patente ya está registrada en otra cuenta")

        usuario = obtener_datos_usuario(cursor, usuario_id)
        if not usuario:
            raise HTTPException(status_code=404, detail="Usuario no encontrado")
        return {"mensaje": "Vehículo actualizado", "usuario": serializar_usuario(usuario)}


@app.post("/usuario/recargar")
def recargar_saldo(datos: RecargaRequest, usuario_id: int = Depends(obtener_usuario_actual)):
    if datos.monto > RECARGA_MAXIMA:
        raise HTTPException(status_code=400, detail=f"El monto máximo por recarga es {RECARGA_MAXIMA}")

    with db_cursor() as (conn, cursor):
        iniciar_transaccion(conn)
        # FOR UPDATE bloquea la fila: un cobro simultáneo espera a que termine la recarga
        cursor.execute("SELECT saldo, activo FROM usuarios WHERE id = %s FOR UPDATE", (usuario_id,))
        usuario = cursor.fetchone()
        if not usuario:
            raise HTTPException(status_code=404, detail="Usuario no encontrado")
        if not usuario["activo"]:
            raise HTTPException(status_code=403, detail="Usuario inactivo, no puede recargar")

        saldo_anterior = usuario["saldo"]
        saldo_nuevo = saldo_anterior + datos.monto
        cursor.execute("UPDATE usuarios SET saldo = saldo + %s WHERE id = %s", (datos.monto, usuario_id))
        cursor.execute(
            "INSERT INTO recargas (usuario_id, monto, saldo_anterior, saldo_nuevo) VALUES (%s, %s, %s, %s)",
            (usuario_id, datos.monto, saldo_anterior, saldo_nuevo),
        )
        conn.commit()

        return {
            "mensaje": "Recarga exitosa",
            "saldo_anterior": float(saldo_anterior),
            "monto_recargado": float(datos.monto),
            "saldo_actual": float(saldo_nuevo),
        }


@app.get("/usuario/historial")
def obtener_historial(
    usuario_id: int = Depends(obtener_usuario_actual),
    limite: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
):
    with db_cursor() as (_, cursor):
        cursor.execute(
            """
            SELECT id, metodo, uid_detectado, garita, carril, patente, tipo_vehiculo, saldo, tarifa_cobrada,
                   saldo_restante, estado, fecha_hora
            FROM transacciones_peaje
            WHERE usuario_id = %s
            ORDER BY fecha_hora DESC, id DESC
            LIMIT %s OFFSET %s
            """,
            (usuario_id, limite, offset),
        )
        transacciones = cursor.fetchall()

    for t in transacciones:
        t["saldo"] = a_float(t["saldo"])
        t["tarifa_cobrada"] = float(t["tarifa_cobrada"])
        t["saldo_restante"] = a_float(t["saldo_restante"])
        t["fecha_hora"] = t["fecha_hora"].strftime("%Y-%m-%d %H:%M:%S")

    return {"usuario_id": usuario_id, "limite": limite, "offset": offset, "historial": transacciones}


@app.get("/usuario/recargas")
def obtener_recargas(
    usuario_id: int = Depends(obtener_usuario_actual),
    limite: int = Query(50, ge=1, le=200),
    offset: int = Query(0, ge=0),
):
    with db_cursor() as (_, cursor):
        cursor.execute(
            """
            SELECT id, monto, saldo_anterior, saldo_nuevo, fecha_hora
            FROM recargas WHERE usuario_id = %s
            ORDER BY fecha_hora DESC, id DESC
            LIMIT %s OFFSET %s
            """,
            (usuario_id, limite, offset),
        )
        recargas = cursor.fetchall()

    for r in recargas:
        for campo in ("monto", "saldo_anterior", "saldo_nuevo"):
            r[campo] = float(r[campo])
        r["fecha_hora"] = r["fecha_hora"].strftime("%Y-%m-%d %H:%M:%S")

    return {"usuario_id": usuario_id, "recargas": recargas}


# --- Token NFC dinámico para HCE (Host Card Emulation) ---

@app.post("/usuario/token-nfc")
def emitir_token_nfc(usuario_id: int = Depends(obtener_usuario_actual)):
    """Token de un solo uso y corta duración que la app entrega al lector por APDU."""
    with db_cursor() as (_, cursor):
        cursor.execute("SELECT activo FROM usuarios WHERE id = %s", (usuario_id,))
        usuario = cursor.fetchone()
    if not usuario:
        raise HTTPException(status_code=404, detail="Usuario no encontrado")
    if not usuario["activo"]:
        raise HTTPException(status_code=403, detail="La cuenta de usuario está inactiva")

    token, expira = generar_token_nfc(usuario_id)
    return {
        "token": token,
        "formato": "hex",
        "bytes": TOKEN_NFC_BYTES,
        "expira": datetime.fromtimestamp(expira, timezone.utc).isoformat(),
        "valido_por_segundos": NFC_TOKEN_TTL,
    }


# --- Credenciales (tarjetas físicas y UID virtual) ---

def serializar_credencial(c) -> dict:
    return {
        "id": c["id"],
        "tipo": c["tipo"],
        "uid": c["uid"],
        "alias": c["alias"],
        "activa": bool(c["activa"]),
        "creada_en": c["creada_en"].strftime("%Y-%m-%d %H:%M:%S"),
    }


@app.get("/usuario/tarjetas")
def listar_tarjetas(usuario_id: int = Depends(obtener_usuario_actual)):
    with db_cursor() as (_, cursor):
        cursor.execute(
            "SELECT id, tipo, uid, alias, activa, creada_en FROM credenciales WHERE usuario_id = %s ORDER BY id",
            (usuario_id,),
        )
        return {"tarjetas": [serializar_credencial(c) for c in cursor.fetchall()]}


@app.post("/usuario/tarjetas", status_code=status.HTTP_201_CREATED)
def vincular_tarjeta(datos: TarjetaRequest, usuario_id: int = Depends(obtener_usuario_actual)):
    uid = normalizar_uid(datos.uid, estricto=True)
    if not uid:
        raise HTTPException(
            status_code=400,
            detail="UID inválido: debe ser hexadecimal de 4, 7 o 10 bytes (8, 14 o 20 caracteres)",
        )

    with db_cursor() as (conn, cursor):
        cursor.execute("SELECT usuario_id FROM credenciales WHERE uid = %s", (uid,))
        existente = cursor.fetchone()
        if existente:
            if existente["usuario_id"] == usuario_id:
                raise HTTPException(status_code=409, detail="Esta tarjeta ya está vinculada a tu cuenta")
            raise HTTPException(status_code=409, detail="Esta tarjeta ya está vinculada a otra cuenta")
        try:
            cursor.execute(
                "INSERT INTO credenciales (usuario_id, tipo, uid, alias) VALUES (%s, 'TARJETA', %s, %s)",
                (usuario_id, uid, (datos.alias or "").strip() or None),
            )
            nuevo_id = cursor.lastrowid
            conn.commit()
        except mysql.connector.IntegrityError:
            raise HTTPException(status_code=409, detail="Esta tarjeta ya está vinculada")

        cursor.execute("SELECT id, tipo, uid, alias, activa, creada_en FROM credenciales WHERE id = %s", (nuevo_id,))
        return {"mensaje": "Tarjeta vinculada", "tarjeta": serializar_credencial(cursor.fetchone())}


@app.delete("/usuario/tarjetas/{tarjeta_id}")
def desvincular_tarjeta(tarjeta_id: int, usuario_id: int = Depends(obtener_usuario_actual)):
    with db_cursor() as (conn, cursor):
        cursor.execute(
            "SELECT id, tipo FROM credenciales WHERE id = %s AND usuario_id = %s",
            (tarjeta_id, usuario_id),
        )
        credencial = cursor.fetchone()
        if not credencial:
            raise HTTPException(status_code=404, detail="Tarjeta no encontrada")
        if credencial["tipo"] != "TARJETA":
            raise HTTPException(status_code=400, detail="El UID virtual no se puede eliminar")
        cursor.execute("DELETE FROM credenciales WHERE id = %s", (tarjeta_id,))
        conn.commit()
        return {"mensaje": "Tarjeta desvinculada"}
