#!/bin/sh
# Genera usuarios y ACL de Mosquitto desde variables de entorno y arranca el broker.
#
#   MQTT_WORKER_USER / MQTT_WORKER_PASSWORD  -> usuario del worker (lee lecturas, publica órdenes)
#   MQTT_DISPOSITIVOS="garita_norte:clave1,garita_sur:clave2"
#       -> un usuario por ESP32. El nombre de usuario DEBE ser el código de la garita:
#          sólo podrá publicar en peaje/<su_garita>/+/lectura|token y leer peaje/<su_garita>/+/orden.
#          (las claves no pueden contener comas)
set -eu

: "${MQTT_WORKER_USER:?Falta MQTT_WORKER_USER}"
: "${MQTT_WORKER_PASSWORD:?Falta MQTT_WORKER_PASSWORD}"
: "${MQTT_DISPOSITIVOS:?Falta MQTT_DISPOSITIVOS}"

PASSWD=/mosquitto/data/passwd
ACL=/mosquitto/data/acl

rm -f "$PASSWD" "$ACL"
touch "$PASSWD"
chmod 0700 "$PASSWD"
mosquitto_passwd -b "$PASSWD" "$MQTT_WORKER_USER" "$MQTT_WORKER_PASSWORD"

OLD_IFS=$IFS
IFS=','
for par in $MQTT_DISPOSITIVOS; do
    usuario=$(echo "${par%%:*}" | tr -d ' ')
    clave=${par#*:}
    if [ -z "$usuario" ] || [ "$usuario" = "$par" ] || [ -z "$clave" ]; then
        echo "Entrada inválida en MQTT_DISPOSITIVOS (formato garita:clave)" >&2
        exit 1
    fi
    mosquitto_passwd -b "$PASSWD" "$usuario" "$clave"
    echo "Dispositivo MQTT registrado: $usuario"
done
IFS=$OLD_IFS

cat > "$ACL" <<EOF
# Worker: recibe lecturas de todas las garitas y es el ÚNICO que puede ordenar abrir la barrera
user $MQTT_WORKER_USER
topic read peaje/+/+/lectura
topic read peaje/+/+/token
topic write peaje/+/+/orden

# Cada ESP32 (usuario = código de garita) sólo opera sobre su propia garita
pattern write peaje/%u/+/lectura
pattern write peaje/%u/+/token
pattern read peaje/%u/+/orden
EOF

chown -R mosquitto:mosquitto /mosquitto/data
chmod 0700 "$ACL"

exec mosquitto -c /mosquitto/config/mosquitto.conf
