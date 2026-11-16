"""Autenticación JWT compartida por la API de la app y la del panel de administración."""
import os
from datetime import datetime, timedelta, timezone

import jwt
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from common import requerido

JWT_SECRET = requerido("JWT_SECRET")
if len(JWT_SECRET) < 32:
    raise RuntimeError("JWT_SECRET debe tener al menos 32 caracteres")
JWT_ALGORITHM = "HS256"
JWT_EXPIRATION_HOURS = int(os.getenv("JWT_EXPIRATION_HOURS", 24))
# Las sesiones de administrador duran menos: un token de admin robado sirve por menos tiempo.
ADMIN_JWT_HORAS = int(os.getenv("ADMIN_JWT_HORAS", 8))

security = HTTPBearer()


def crear_token_jwt(usuario_id: int, correo: str, rol: str = "USUARIO") -> str:
    horas = min(JWT_EXPIRATION_HOURS, ADMIN_JWT_HORAS) if rol == "ADMIN" else JWT_EXPIRATION_HOURS
    expiracion = datetime.now(timezone.utc) + timedelta(hours=horas)
    payload = {"sub": str(usuario_id), "correo": correo, "rol": rol, "exp": expiracion}
    return jwt.encode(payload, JWT_SECRET, algorithm=JWT_ALGORITHM)


def obtener_usuario_actual(credentials: HTTPAuthorizationCredentials = Depends(security)) -> int:
    """Dependencia que valida el token Bearer y devuelve el ID del usuario autenticado."""
    try:
        payload = jwt.decode(credentials.credentials, JWT_SECRET, algorithms=[JWT_ALGORITHM])
        return int(payload.get("sub"))
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=401, detail="El token ha expirado, inicia sesión nuevamente")
    except (jwt.InvalidTokenError, TypeError, ValueError):
        raise HTTPException(status_code=401, detail="Token de autorización inválido")
