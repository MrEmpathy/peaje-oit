"""Worker MQTT del peaje.

Topics que escucha (publicados por el ESP32):
  peaje/<garita>/<carril>/lectura  -> UID de tarjeta (opción 2, compatible con RC522)
  peaje/<garita>/<carril>/token    -> token dinámico entregado por la app vía HCE/APDU (opción 1)

Responde en:
  peaje/<garita>/<carril>/orden    -> "ABRIR" / "DENEGAR"

Formatos de payload aceptados:
  - Texto plano:  "A1B2C3D4"                      -> respuesta en texto plano: "ABRIR"
  - JSON:         {"id": "123", "uid": "A1B2C3D4"} -> respuesta JSON:
                  {"id": "123", "orden": "ABRIR", "estado": "APROBADO", "saldo": 4100.0,
                   "tarifa": 900.0, "patente": "BCDF12", "tipo_vehiculo": "AUTO"}

La tarifa depende de la garita y del tipo de vehículo del usuario (tabla tarifas).
"""
import json
import logging
import os
import time
from dataclasses import dataclass
from decimal import Decimal
from typing import Optional

import mysql.connector
import paho.mqtt.client as mqtt

from common import (
    TokenNFCError,
    env_bool,
    get_db_connection,
    iniciar_transaccion,
    normalizar_uid,
    requerido,
    verificar_token_nfc,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("worker_mqtt")

MQTT_BROKER = os.getenv("MQTT_BROKER", "127.0.0.1")
MQTT_PORT = int(os.getenv("MQTT_PORT", 1883))
MQTT_USER = requerido("MQTT_WORKER_USER")
MQTT_PASSWORD = requerido("MQTT_WORKER_PASSWORD")

# Si la misma persona pasa por la misma garita dentro de esta ventana, no se cobra de nuevo:
# se responde ABRIR sin descontar (cubre reintentos del ESP32 y tarjetas apoyadas en el lector).
ANTI_DOBLE_COBRO_SEGUNDOS = int(os.getenv("ANTI_DOBLE_COBRO_SEGUNDOS", 10))
# Opción 2 (cobro por UID). Poner en false cuando todos los lectores usen token HCE.
PERMITIR_COBRO_UID = env_bool("PERMITIR_COBRO_UID", True)

TOPIC_LECTURA = "peaje/+/+/lectura"
TOPIC_TOKEN = "peaje/+/+/token"
QOS = 1
CERO = Decimal("0.00")

_ultima_limpieza = 0.0


@dataclass
class Resultado:
    orden: str                          # ABRIR | DENEGAR
    estado: str                         # valor de transacciones_peaje.estado
    saldo: Optional[Decimal] = None
    tarifa: Optional[Decimal] = None
    patente: Optional[str] = None
    tipo_vehiculo: Optional[str] = None


def denegar(estado: str, **extra) -> Resultado:
    return Resultado("DENEGAR", estado, **extra)


# --- Persistencia ---

def registrar_transaccion(cursor, *, usuario_id, credencial_id, metodo, uid_detectado, garita, carril,
                          saldo, tarifa, saldo_restante, estado, lectura_id, patente=None, tipo_vehiculo=None):
    cursor.execute(
        """
        INSERT INTO transacciones_peaje
            (usuario_id, credencial_id, metodo, uid_detectado, garita, carril, patente, tipo_vehiculo,
             saldo, tarifa_cobrada, saldo_restante, estado, lectura_id)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        """,
        (usuario_id, credencial_id, metodo, uid_detectado, garita, carril, patente, tipo_vehiculo,
         saldo, tarifa, saldo_restante, estado, lectura_id),
    )


def cobrar(conn, *, usuario_id, garita_id, credencial_id, metodo, uid_detectado, garita, carril,
           lectura_id, nonce=None, nonce_expira=None) -> Resultado:
    """Cobro atómico: bloquea la fila del usuario, obtiene la tarifa según su tipo de vehículo,
    valida, descuenta y registra en una sola transacción."""
    cursor = conn.cursor(dictionary=True)
    base = dict(usuario_id=usuario_id, credencial_id=credencial_id, metodo=metodo,
                uid_detectado=uid_detectado, garita=garita, carril=carril, lectura_id=lectura_id)
    try:
        iniciar_transaccion(conn)
        cursor.execute(
            """
            SELECT nombre, apellido, saldo, activo, patente, tipo_vehiculo_id
            FROM usuarios WHERE id = %s FOR UPDATE
            """,
            (usuario_id,),
        )
        usuario = cursor.fetchone()

        if not usuario:
            base["usuario_id"] = None
            registrar_transaccion(cursor, **base, saldo=None, tarifa=CERO, saldo_restante=None,
                                  estado="USUARIO_NO_ENCONTRADO")
            conn.commit()
            return denegar("USUARIO_NO_ENCONTRADO")

        # Tarifa de esta garita para el tipo de vehículo del usuario (lectura sin bloqueo)
        cursor.execute(
            """
            SELECT tv.codigo, t.monto
            FROM tipos_vehiculo tv
            LEFT JOIN tarifas t ON t.tipo_vehiculo_id = tv.id AND t.garita_id = %s
            WHERE tv.id = %s
            """,
            (garita_id, usuario["tipo_vehiculo_id"]),
        )
        fila_tarifa = cursor.fetchone() or {}
        tipo = fila_tarifa.get("codigo")
        tarifa = fila_tarifa.get("monto")

        saldo = usuario["saldo"]
        nombre = f"{usuario['nombre']} {usuario['apellido']}"
        base.update(patente=usuario["patente"], tipo_vehiculo=tipo)
        info = dict(saldo=saldo, tarifa=tarifa, patente=usuario["patente"], tipo_vehiculo=tipo)

        if not usuario["activo"]:
            registrar_transaccion(cursor, **base, saldo=saldo, tarifa=tarifa or CERO, saldo_restante=saldo,
                                  estado="TRANSACCION_INVALIDA")
            conn.commit()
            log.info("[%s/%s] Usuario %s inactivo -> DENEGAR", garita, carril, nombre)
            return denegar("TRANSACCION_INVALIDA", **info)

        if tarifa is None:
            registrar_transaccion(cursor, **base, saldo=saldo, tarifa=CERO, saldo_restante=saldo,
                                  estado="TARIFA_NO_DEFINIDA")
            conn.commit()
            log.error("[%s/%s] No hay tarifa definida para tipo %s en esta garita -> DENEGAR",
                      garita, carril, tipo)
            return denegar("TARIFA_NO_DEFINIDA", **info)

        # Anti doble cobro (se evalúa con la fila bloqueada, así dos lecturas simultáneas no pasan ambas)
        if ANTI_DOBLE_COBRO_SEGUNDOS > 0:
            cursor.execute(
                """
                SELECT id FROM transacciones_peaje
                WHERE usuario_id = %s AND garita = %s AND estado = 'APROBADO'
                  AND fecha_hora >= NOW() - INTERVAL %s SECOND
                LIMIT 1
                """,
                (usuario_id, garita, ANTI_DOBLE_COBRO_SEGUNDOS),
            )
            if cursor.fetchone():
                registrar_transaccion(cursor, **base, saldo=saldo, tarifa=CERO, saldo_restante=saldo,
                                      estado="LECTURA_DUPLICADA")
                conn.commit()
                log.info("[%s/%s] Lectura duplicada de %s -> ABRIR sin cobro", garita, carril, nombre)
                return Resultado("ABRIR", "LECTURA_DUPLICADA", saldo, CERO, usuario["patente"], tipo)

        if saldo < tarifa:
            registrar_transaccion(cursor, **base, saldo=saldo, tarifa=tarifa, saldo_restante=saldo,
                                  estado="SALDO_INSUFICIENTE")
            conn.commit()
            log.info("[%s/%s] Saldo insuficiente (%s < %s) para %s -> DENEGAR", garita, carril, saldo, tarifa, nombre)
            return denegar("SALDO_INSUFICIENTE", **info)

        # Token de un solo uso: se marca como usado sólo si el cobro va a aprobarse
        if nonce:
            try:
                cursor.execute(
                    "INSERT INTO tokens_nfc_usados (nonce, usuario_id, expira) VALUES (%s, %s, FROM_UNIXTIME(%s))",
                    (nonce, usuario_id, nonce_expira),
                )
            except mysql.connector.IntegrityError:
                registrar_transaccion(cursor, **base, saldo=saldo, tarifa=tarifa, saldo_restante=saldo,
                                      estado="TOKEN_REUTILIZADO")
                conn.commit()
                log.warning("[%s/%s] Token reutilizado por %s -> DENEGAR", garita, carril, nombre)
                return denegar("TOKEN_REUTILIZADO", **info)

        cursor.execute(
            "UPDATE usuarios SET saldo = saldo - %s WHERE id = %s AND saldo >= %s",
            (tarifa, usuario_id, tarifa),
        )
        if cursor.rowcount != 1:  # no debería ocurrir con la fila bloqueada; por seguridad
            conn.rollback()
            return denegar("SALDO_INSUFICIENTE", **info)

        saldo_restante = saldo - tarifa
        registrar_transaccion(cursor, **base, saldo=saldo, tarifa=tarifa, saldo_restante=saldo_restante,
                              estado="APROBADO")
        conn.commit()
        log.info("[%s/%s] Cobro APROBADO ($%s, %s %s, %s) a %s. Saldo restante: %s -> ABRIR",
                 garita, carril, tarifa, tipo, usuario["patente"], metodo, nombre, saldo_restante)
        return Resultado("ABRIR", "APROBADO", saldo_restante, tarifa, usuario["patente"], tipo)
    except Exception:
        conn.rollback()
        raise
    finally:
        cursor.close()


def obtener_garita_id(cursor, garita: str) -> Optional[int]:
    cursor.execute("SELECT id, activa FROM garitas WHERE codigo = %s", (garita,))
    info = cursor.fetchone()
    if not info or not info["activa"]:
        return None
    return info["id"]


def procesar_uid(conn, garita, carril, uid_raw, lectura_id) -> Resultado:
    """Opción 2: cobro por UID de tarjeta física o UID virtual."""
    cursor = conn.cursor(dictionary=True)
    try:
        uid = normalizar_uid(uid_raw)
        base = dict(credencial_id=None, metodo="UID", uid_detectado=(uid or str(uid_raw or "")[:64] or None),
                    garita=garita, carril=carril, lectura_id=lectura_id)
        sin_cobro = dict(saldo=None, tarifa=CERO, saldo_restante=None)

        garita_id = obtener_garita_id(cursor, garita)
        if garita_id is None:
            registrar_transaccion(cursor, usuario_id=None, **base, **sin_cobro, estado="TRANSACCION_INVALIDA")
            conn.commit()
            log.warning("[%s/%s] Garita no registrada o inactiva -> DENEGAR", garita, carril)
            return denegar("TRANSACCION_INVALIDA")

        if not PERMITIR_COBRO_UID:
            registrar_transaccion(cursor, usuario_id=None, **base, **sin_cobro, estado="METODO_DESHABILITADO")
            conn.commit()
            return denegar("METODO_DESHABILITADO")

        if not uid:
            registrar_transaccion(cursor, usuario_id=None, **base, **sin_cobro, estado="TRANSACCION_INVALIDA")
            conn.commit()
            log.info("[%s/%s] UID inválido: %r -> DENEGAR", garita, carril, uid_raw)
            return denegar("TRANSACCION_INVALIDA")

        cursor.execute("SELECT id, usuario_id, activa FROM credenciales WHERE uid = %s", (uid,))
        credencial = cursor.fetchone()
        if not credencial:
            registrar_transaccion(cursor, usuario_id=None, **base, **sin_cobro, estado="USUARIO_NO_ENCONTRADO")
            conn.commit()
            log.info("[%s/%s] UID %s no vinculado a ningún usuario -> DENEGAR", garita, carril, uid)
            return denegar("USUARIO_NO_ENCONTRADO")

        base["credencial_id"] = credencial["id"]
        if not credencial["activa"]:
            registrar_transaccion(cursor, usuario_id=credencial["usuario_id"], **base, **sin_cobro,
                                  estado="CREDENCIAL_INACTIVA")
            conn.commit()
            return denegar("CREDENCIAL_INACTIVA")
    finally:
        cursor.close()

    return cobrar(conn, usuario_id=credencial["usuario_id"], garita_id=garita_id, **base)


def procesar_token(conn, garita, carril, token_raw, lectura_id) -> Resultado:
    """Opción 1: cobro por token dinámico HCE (requiere lector compatible con ISO 14443-4, ej. PN532)."""
    cursor = conn.cursor(dictionary=True)
    base = dict(credencial_id=None, metodo="TOKEN", uid_detectado="HCE",
                garita=garita, carril=carril, lectura_id=lectura_id)
    sin_cobro = dict(saldo=None, tarifa=CERO, saldo_restante=None)
    try:
        garita_id = obtener_garita_id(cursor, garita)
        if garita_id is None:
            registrar_transaccion(cursor, usuario_id=None, **base, **sin_cobro, estado="TRANSACCION_INVALIDA")
            conn.commit()
            log.warning("[%s/%s] Garita no registrada o inactiva -> DENEGAR", garita, carril)
            return denegar("TRANSACCION_INVALIDA")

        try:
            usuario_id, nonce, expira = verificar_token_nfc(token_raw)
        except TokenNFCError as e:
            registrar_transaccion(cursor, usuario_id=e.usuario_id, **base, **sin_cobro, estado=e.estado)
            conn.commit()
            log.info("[%s/%s] %s -> DENEGAR", garita, carril, e.estado)
            return denegar(e.estado)
    finally:
        cursor.close()

    return cobrar(conn, usuario_id=usuario_id, garita_id=garita_id, nonce=nonce, nonce_expira=expira, **base)


def limpiar_tokens_vencidos(conn):
    """Borra cada 10 minutos los nonces de tokens que ya expiraron (ya no pueden reutilizarse)."""
    global _ultima_limpieza
    if time.monotonic() - _ultima_limpieza < 600:
        return
    _ultima_limpieza = time.monotonic()
    cursor = conn.cursor()
    try:
        conn.commit()
        cursor.execute("DELETE FROM tokens_nfc_usados WHERE expira < NOW() - INTERVAL 1 HOUR")
        conn.commit()
    finally:
        cursor.close()


# --- MQTT ---

def parsear_payload(raw: bytes):
    """Devuelve (lectura_id, valor, es_json)."""
    texto = raw.decode("utf-8", errors="replace").strip()
    if texto.startswith("{"):
        try:
            datos = json.loads(texto)
        except json.JSONDecodeError:
            return None, "", True
        lectura_id = datos.get("id")
        valor = datos.get("uid") or datos.get("token") or ""
        return (str(lectura_id)[:64] if lectura_id is not None else None), str(valor), True
    return None, texto, False


def _num(valor):
    return float(valor) if valor is not None else None


def responder(client, garita, carril, lectura_id, es_json, resultado: Resultado):
    topic = f"peaje/{garita}/{carril}/orden"
    if es_json:
        payload = json.dumps({
            "id": lectura_id,
            "orden": resultado.orden,
            "estado": resultado.estado,
            "saldo": _num(resultado.saldo),
            "tarifa": _num(resultado.tarifa),
            "patente": resultado.patente,
            "tipo_vehiculo": resultado.tipo_vehiculo,
        })
    else:
        payload = resultado.orden
    client.publish(topic, payload, qos=QOS)


def on_connect(client, userdata, flags, reason_code, properties=None):
    if reason_code.is_failure:
        log.error("[MQTT] Conexión rechazada por el broker: %s (¿usuario/clave MQTT correctos?)", reason_code)
        return
    log.info("[MQTT] Conectado al broker %s:%s", MQTT_BROKER, MQTT_PORT)
    client.subscribe([(TOPIC_LECTURA, QOS), (TOPIC_TOKEN, QOS)])


def on_disconnect(client, userdata, flags, reason_code, properties=None):
    log.warning("[MQTT] Desconectado del broker (%s). Reintentando...", reason_code)


def on_message(client, userdata, msg):
    partes = msg.topic.split("/")
    if len(partes) != 4 or partes[0] != "peaje" or partes[3] not in ("lectura", "token"):
        return
    _, garita, carril, tipo = partes
    lectura_id, valor, es_json = parsear_payload(msg.payload)
    log.info("[MQTT] %s en %s/%s (id=%s)", tipo, garita, carril, lectura_id)

    conn = None
    try:
        conn = get_db_connection()
        if tipo == "lectura":
            resultado = procesar_uid(conn, garita, carril, valor, lectura_id)
        else:
            resultado = procesar_token(conn, garita, carril, valor, lectura_id)
        limpiar_tokens_vencidos(conn)
    except Exception:
        # Ante cualquier fallo (BD caída, etc.) la barrera NO se abre
        log.exception("[%s/%s] Error procesando lectura -> DENEGAR", garita, carril)
        resultado = denegar("ERROR_SISTEMA")
    finally:
        if conn:
            try:
                conn.close()
            except Exception:
                pass

    responder(client, garita, carril, lectura_id, es_json, resultado)


def main():
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="peaje-worker")
    client.username_pw_set(MQTT_USER, MQTT_PASSWORD)
    client.on_connect = on_connect
    client.on_disconnect = on_disconnect
    client.on_message = on_message
    client.reconnect_delay_set(min_delay=1, max_delay=30)

    log.info("Cobro por UID: %s | Anti doble cobro: %ss",
             "habilitado" if PERMITIR_COBRO_UID else "deshabilitado", ANTI_DOBLE_COBRO_SEGUNDOS)
    while True:
        try:
            client.connect(MQTT_BROKER, MQTT_PORT, keepalive=60)
            break
        except Exception as e:
            log.info("[MQTT] Esperando al broker Mosquitto... (%s)", e)
            time.sleep(2)

    client.loop_forever()


if __name__ == "__main__":
    main()
