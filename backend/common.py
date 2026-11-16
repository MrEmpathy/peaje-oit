"""Utilidades compartidas entre la API REST y el worker MQTT."""
import hashlib
import hmac
import os
import re
import secrets
import struct
import time
from contextlib import contextmanager

import mysql.connector


def requerido(nombre: str) -> str:
    """Lee una variable de entorno obligatoria; si falta, el servicio no arranca."""
    valor = os.getenv(nombre)
    if not valor:
        raise RuntimeError(f"Falta la variable de entorno obligatoria {nombre}")
    return valor


def env_bool(nombre: str, por_defecto: bool) -> bool:
    valor = os.getenv(nombre)
    if valor is None:
        return por_defecto
    return valor.strip().lower() in ("1", "true", "si", "sí", "yes", "on")


# --- Base de datos ---

DB_CONFIG = {
    "host": os.getenv("DB_HOST", "127.0.0.1"),
    "port": int(os.getenv("DB_PORT", 3306)),
    "user": requerido("DB_USER"),
    "password": requerido("DB_PASSWORD"),
    "database": os.getenv("DB_NAME", "peaje_iot"),
    "autocommit": False,
}


def get_db_connection():
    return mysql.connector.connect(**DB_CONFIG)


@contextmanager
def db_cursor():
    """Entrega (conn, cursor). Si ocurre una excepción hace rollback; siempre cierra."""
    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    try:
        yield conn, cursor
    except Exception:
        conn.rollback()
        raise
    finally:
        cursor.close()
        conn.close()


def iniciar_transaccion(conn):
    """Cierra cualquier transacción implícita abierta y empieza una nueva en READ COMMITTED,
    para que las lecturas posteriores a un SELECT ... FOR UPDATE vean los datos más recientes."""
    conn.commit()
    conn.start_transaction(isolation_level="READ COMMITTED")


# --- UID de tarjetas ---

_HEX = re.compile(r"^[0-9A-F]+$")


def normalizar_uid(uid_raw: str, estricto: bool = False):
    """Devuelve el UID en hexadecimal mayúscula sin separadores, o None si no es válido.

    estricto=True exige un UID ISO 14443 completo (4, 7 o 10 bytes) -> se usa al vincular tarjetas.
    estricto=False acepta 2..10 bytes -> se usa en las lecturas del ESP32.
    """
    if uid_raw is None:
        return None
    uid = re.sub(r"[\s:\-]", "", str(uid_raw)).upper()
    if uid.startswith("0X"):
        uid = uid[2:]
    if not uid or not _HEX.match(uid):
        return None
    if estricto:
        return uid if len(uid) in (8, 14, 20) else None
    return uid if 4 <= len(uid) <= 20 else None


# --- Patentes ---

_PATENTE = re.compile(r"^(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{5,8}$")


def normalizar_patente(patente_raw: str):
    """'ab-cd·12' -> 'ABCD12'. Exige 5 a 8 caracteres alfanuméricos con al menos una letra y un número
    (cubre formatos chilenos AB1234, BCDF12 y de motos como AB123 / ABC12). Devuelve None si no es válida."""
    if patente_raw is None:
        return None
    patente = re.sub(r"[\s\.\-·_]", "", str(patente_raw)).upper()
    return patente if _PATENTE.match(patente) else None


# --- Token NFC dinámico (HCE) ---
#
# Formato binario (33 bytes, se transmite en hexadecimal = 66 caracteres):
#   versión (1) | usuario_id (4, big-endian) | expira unix (4, big-endian) | nonce (8) | HMAC-SHA256[:16]
# El HMAC se calcula sobre los primeros 17 bytes con NFC_TOKEN_SECRET.

NFC_TOKEN_SECRET = requerido("NFC_TOKEN_SECRET").encode("utf-8")
NFC_TOKEN_TTL = int(os.getenv("NFC_TOKEN_TTL_SEGUNDOS", 300))

_TOKEN_VERSION = 1
_TOKEN_CUERPO = struct.Struct(">BII8s")  # 17 bytes
_TOKEN_MAC_LEN = 16
TOKEN_NFC_BYTES = _TOKEN_CUERPO.size + _TOKEN_MAC_LEN  # 33


class TokenNFCError(Exception):
    def __init__(self, estado: str, usuario_id=None):
        super().__init__(estado)
        self.estado = estado          # TOKEN_INVALIDO | TOKEN_EXPIRADO
        self.usuario_id = usuario_id  # sólo se conoce si la firma era válida


def _mac(cuerpo: bytes) -> bytes:
    return hmac.new(NFC_TOKEN_SECRET, cuerpo, hashlib.sha256).digest()[:_TOKEN_MAC_LEN]


def generar_token_nfc(usuario_id: int, ttl: int = None):
    """Devuelve (token_hex, expira_unix)."""
    expira = int(time.time()) + (ttl or NFC_TOKEN_TTL)
    cuerpo = _TOKEN_CUERPO.pack(_TOKEN_VERSION, usuario_id, expira, secrets.token_bytes(8))
    return (cuerpo + _mac(cuerpo)).hex().upper(), expira


def verificar_token_nfc(token_hex: str):
    """Valida firma y vigencia. Devuelve (usuario_id, nonce_hex, expira_unix) o lanza TokenNFCError."""
    try:
        datos = bytes.fromhex(re.sub(r"[\s:\-]", "", token_hex or ""))
    except ValueError:
        raise TokenNFCError("TOKEN_INVALIDO")
    if len(datos) != TOKEN_NFC_BYTES:
        raise TokenNFCError("TOKEN_INVALIDO")

    cuerpo, mac = datos[:_TOKEN_CUERPO.size], datos[_TOKEN_CUERPO.size:]
    if not hmac.compare_digest(mac, _mac(cuerpo)):
        raise TokenNFCError("TOKEN_INVALIDO")

    version, usuario_id, expira, nonce = _TOKEN_CUERPO.unpack(cuerpo)
    if version != _TOKEN_VERSION:
        raise TokenNFCError("TOKEN_INVALIDO")
    if expira < int(time.time()):
        raise TokenNFCError("TOKEN_EXPIRADO", usuario_id)
    return usuario_id, nonce.hex().upper(), expira
