#!/bin/bash
# Puesta en marcha COMPLETA del backend en este PC (Arch/CachyOS) con Docker.
#  - Instala Docker + Compose + Buildx y habilita el servicio
#  - Agrega tu usuario al grupo docker (efectivo tras volver a iniciar sesión)
#  - Crea la BD peaje_iot en el MariaDB del host (solo si no existe) y el usuario del .env
#  - Construye y levanta los contenedores (mosquitto, API, worker)
# Uso:  sudo ./setup_docker.sh
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a

pacman -S --needed --noconfirm docker docker-compose docker-buildx
systemctl enable --now docker
[ -n "${SUDO_USER:-}" ] && usermod -aG docker "$SUDO_USER"

if mariadb -N -e "SHOW DATABASES LIKE '${DB_NAME}'" | grep -q .; then
    echo "La base ${DB_NAME} ya existe: no se toca."
else
    mariadb < db/schema.sql
    echo "Base ${DB_NAME} creada."
fi
for h in localhost 127.0.0.1; do
    mariadb -e "CREATE USER IF NOT EXISTS '${DB_USER}'@'${h}' IDENTIFIED BY '${DB_PASSWORD}';
                ALTER USER '${DB_USER}'@'${h}' IDENTIFIED BY '${DB_PASSWORD}';
                GRANT SELECT, INSERT, UPDATE, DELETE ON ${DB_NAME}.* TO '${DB_USER}'@'${h}';"
done
mariadb -e "FLUSH PRIVILEGES;"

docker compose up -d --build --remove-orphans
docker compose ps
echo
echo "Probando la API..."
for i in $(seq 1 20); do
    curl -fsS http://127.0.0.1:8000/salud && { echo; echo "Backend OK"; exit 0; }
    sleep 1
done
echo "La API no respondió; revisa: docker compose logs api_fastapi"
