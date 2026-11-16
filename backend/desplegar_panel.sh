#!/usr/bin/env bash
# Activa el panel web de administración en este servidor.
#   sudo bash backend/desplegar_panel.sh tu_correo@ejemplo.cl
# 1) migra la base (agrega rol, ajustes y auditoría; NO borra datos)
# 2) reconstruye y reinicia solo el contenedor de la API (worker y Mosquitto siguen corriendo)
# 3) da rol de administrador a la cuenta indicada (debe estar registrada desde la app)
set -euo pipefail
cd "$(dirname "$0")"

CORREO="${1:-}"
if [[ $EUID -ne 0 ]]; then
  echo "Ejecuta con sudo:  sudo bash $0 tu_correo@ejemplo.cl"; exit 1
fi
DB_NAME=$(grep -E '^DB_NAME=' .env | cut -d= -f2- || true); DB_NAME=${DB_NAME:-peaje_iot}
DB_USER=$(grep -E '^DB_USER=' .env | cut -d= -f2-)

echo "== 1/3 Migrando la base $DB_NAME =="
mariadb "$DB_NAME" < db/migracion_admin.sql
# El usuario de la API necesita los mismos permisos sobre las tablas nuevas (por si los permisos eran por tabla)
for HOST in $(mariadb -N -e "SELECT host FROM mysql.user WHERE user='$DB_USER'"); do
  mariadb -e "GRANT SELECT, INSERT, UPDATE, DELETE ON \`$DB_NAME\`.* TO '$DB_USER'@'$HOST'"
done
mariadb "$DB_NAME" -e "SHOW COLUMNS FROM usuarios LIKE 'rol'" | grep -q rol && echo "   OK: columna rol y tablas nuevas"

echo "== 2/3 Reconstruyendo la API con el panel =="
docker compose up -d --build api_fastapi
for i in $(seq 1 40); do
  curl -sf -m 2 http://127.0.0.1:8000/salud >/dev/null && break; sleep 1
done
curl -sf -m 3 -o /dev/null http://127.0.0.1:8000/panel/ && echo "   OK: panel en http://127.0.0.1:8000/panel/" \
  || { echo "   ERROR: el panel no responde. Revisa: docker compose logs api_fastapi"; exit 1; }

echo "== 3/3 Administrador =="
if [[ -z "$CORREO" ]]; then
  echo "   No indicaste correo. Cuentas registradas:"
  mariadb "$DB_NAME" -N -e "SELECT CONCAT('   - ', correo, '  (', nombre, ' ', apellido, ', rol ', rol, ')') FROM usuarios ORDER BY id"
  echo "   Luego ejecuta:  sudo docker compose -f $(pwd)/docker-compose.yml exec api_fastapi python crear_admin.py <correo>"
else
  docker compose exec -T api_fastapi python crear_admin.py "$CORREO"
fi

IP=$(ip -4 -o addr show scope global | awk '{print $4}' | cut -d/ -f1 | head -1)
echo
echo "Listo. Abre el panel en:  http://${IP:-127.0.0.1}:8000/panel/"
