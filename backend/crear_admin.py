"""Da (o quita) acceso al panel web de administración a una cuenta existente.

La cuenta se crea primero desde la app (Registrarse). Luego, en el server:

    docker compose exec api_fastapi python crear_admin.py correo@ejemplo.cl
    docker compose exec api_fastapi python crear_admin.py correo@ejemplo.cl --quitar

No existe ningún endpoint para hacerse administrador: sólo quien tiene acceso al servidor puede.
"""
import sys

from common import db_cursor


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    quitar = "--quitar" in sys.argv
    if len(args) != 1:
        print(__doc__)
        return 2
    correo = args[0].strip().lower()
    rol = "USUARIO" if quitar else "ADMIN"

    with db_cursor() as (conn, cursor):
        cursor.execute("SELECT id, nombre, apellido, rol, activo FROM usuarios WHERE correo = %s", (correo,))
        usuario = cursor.fetchone()
        if not usuario:
            print(f"No existe una cuenta con el correo {correo}. Regístrala primero desde la app.")
            return 1
        if usuario["rol"] == rol:
            print(f"{correo} ya tiene el rol {rol}. No se cambió nada.")
            return 0
        cursor.execute("UPDATE usuarios SET rol = %s WHERE id = %s", (rol, usuario["id"]))
        cursor.execute(
            "INSERT INTO auditoria_admin (admin_id, accion, detalle, ip) VALUES (NULL, %s, %s, 'consola')",
            ("QUITAR_ADMIN" if quitar else "DAR_ADMIN", f"Usuario #{usuario['id']} ({correo})"),
        )
        conn.commit()

    nombre = f"{usuario['nombre']} {usuario['apellido']}"
    if quitar:
        print(f"{nombre} ({correo}) ya no es administrador.")
    else:
        print(f"{nombre} ({correo}) ahora es administrador. Entra al panel en http://<ip-del-servidor>:8000/panel/")
        if not usuario["activo"]:
            print("Atención: la cuenta está desactivada; no podrá entrar al panel hasta activarla.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
