# Configuración de los firmwares (ESP32)

Hay tres firmwares para la barrera del peaje. Se usa **uno solo**, según el lector NFC que tenga montado el ESP32.

| Carpeta | Lector | Tarjetas físicas | Pago con el celular (app) |
|---|---|---|---|
| `peaje_rc522/` | RC522 (SPI) | Sí (por UID) | No |
| `peaje_rc522_hce/` | RC522 (SPI) | Sí (por UID) | Sí (experimental, ver abajo) |
| `peaje_pn532/` | PN532 (I2C) | Sí (por UID) | Sí (NFC/HCE con token de un solo uso) |

> **`peaje_rc522_hce`**: mismo cableado y librerías que `peaje_rc522`, pero implementa ISO 14443-4 (ISO-DEP)
> a mano sobre el RC522 para leer el token de la app. Funciona igual que el PN532 desde el punto de vista del
> servidor (publica en `.../token`). La antena de los módulos RC522 es débil: el teléfono debe quedar **pegado
> y quieto** sobre el lector (con la zona NFC del teléfono centrada). Si falla seguido, usar el PN532.
> En el Monitor Serie, `ISO-DEP activo` indica que el teléfono respondió; `Sin respuesta a RATS` o
> `Sin respuesta valida del telefono` indican un problema de acople (acercar más o cambiar la posición).

Ambos usan el mismo servidor, la misma LCD, servo y sensor ultrasónico, y los mismos topics MQTT.

---

## 1. Requisitos

- **Arduino IDE 2** con el core **esp32** de Espressif (Gestor de placas → "esp32").
- Placa: **ESP32 Dev Module** (`esp32:esp32:esp32`).
- Librerías (Gestor de librerías, salvo la PN532):

| Librería | Firmware | Cómo instalar |
|---|---|---|
| PubSubClient (Nick O'Leary) | todos | Gestor de librerías |
| ESP32Servo | todos | Gestor de librerías |
| LiquidCrystal I2C (Frank de Brabander) | todos | Gestor de librerías |
| MFRC522 (GithubCommunity) | `peaje_rc522`, `peaje_rc522_hce` | Gestor de librerías |
| **PN532-Arduino (Seeed Studio)** | `peaje_pn532` | *Programa → Incluir librería → Añadir biblioteca .ZIP* → `librerias/PN532-Arduino-Seeed.zip` |

> No uses "Adafruit PN532" para `peaje_pn532`: no permite el intercambio APDU que necesita el pago con celular.

## 2. Configuración (constantes al inicio de cada `.ino`)

| Constante | Valor |
|---|---|
| Wi-Fi (`ssid`/`password` o `WIFI_SSID`/`WIFI_PASSWORD`) | Nombre y clave de tu red |
| Servidor MQTT (`mqtt_server` / `MQTT_SERVER`) | IP de la Raspberry Pi (ej. `192.168.1.191`) |
| `GARITA_ID` | Código de la garita, ej. `garita_norte`. Es **también el usuario MQTT** y debe existir en la tabla `garitas` |
| `CARRIL_ID` | ej. `carril1` |
| Clave MQTT (`mqtt_password` / `MQTT_PASSWORD`) | En el `.env` del servidor, `MQTT_DISPOSITIVOS=garita_norte:<clave>` → copiar `<clave>` |

Para agregar otra garita: sumar `garita_sur:<clave>` a `MQTT_DISPOSITIVOS` en el `.env`, reiniciar el contenedor
`peaje_mqtt`, insertar la garita y sus tarifas en la base de datos (ver `backend/README.md`) y usar `GARITA_ID = "garita_sur"`.

> El `.ino` con la clave real no debe subirse a un repositorio público.

## 3. Conexiones

Comunes a todos los firmwares:

| Componente | Pin ESP32 |
|---|---|
| LCD I2C 16x2 — SDA / SCL | GPIO 21 / GPIO 26 (dirección 0x3F; si no muestra texto, cambiar a 0x27) |
| Servo (barrera) | GPIO 13 |
| HC-SR04 — TRIG / ECHO | GPIO 32 / GPIO 33 |

### RC522 (`peaje_rc522` y `peaje_rc522_hce`)

| RC522 | ESP32 |
|---|---|
| SDA (SS) | GPIO 5 |
| SCK | GPIO 18 |
| MOSI | GPIO 23 |
| MISO | GPIO 19 |
| RST | GPIO 22 |
| 3.3V / GND | 3V3 / GND |

### PN532 (`peaje_pn532`) — modo I2C

El PN532 comparte el bus I2C con la LCD (direcciones distintas: PN532 = 0x24). No hace falta desoldar la LCD:
los cables SDA/SCL del PN532 se unen a los de la LCD **del lado del ESP32** (misma fila de la protoboard o un empalme en "Y").

| PN532 | ESP32 |
|---|---|
| SDA | GPIO 21 (junto con la LCD) |
| SCL | GPIO 26 (junto con la LCD) |
| VCC / GND | 3V3 / GND |

Interruptores del módulo PN532 V3 (rojo) en **modo I2C: SW1 = ON, SW2 = OFF**.
El RC522 se desconecta (sus pines 5, 18, 19, 22 y 23 quedan libres).

## 4. Cargar y probar

1. Abrir la carpeta del firmware en Arduino IDE, completar la configuración y *Subir*.
2. Abrir el **Monitor Serie a 115200 baudios**. Debe aparecer:
   - `[WIFI] Conectado! IP: ...`
   - `[MQTT] ... Conectado!`
   - Con PN532: `[NFC] PN532 firmware 1.6`
3. **Tarjeta física**: debe estar vinculada a una cuenta (`POST /usuario/tarjetas`, ver `backend/README.md`). Acercarla al lector.
4. **Celular** (PN532 o `peaje_rc522_hce`): en la app, tocar la tarjeta azul de Inicio ("Toca para pagar con NFC")
   y acercar la parte trasera del teléfono al lector, con la pantalla encendida y desbloqueada.

Comandos por Monitor Serie (todos los firmwares): `A` abrir valla, `C` cerrar, `D` medir distancia.

## 5. Mensajes de la LCD

| LCD | Significado |
|---|---|
| `PAGO APROBADO` / `Gracias` + `Saldo $...` | Cobro realizado, se abre la valla |
| `PASO REGISTRADO` | Segunda lectura en menos de 10 s: abre sin volver a cobrar |
| `SALDO INSUFIC.` | El saldo no alcanza para la tarifa de su tipo de vehículo |
| `NO REGISTRADA / Vincule tarjeta` | Tarjeta sin cuenta asociada |
| `TARJETA INACTIVA` | Tarjeta desactivada |
| `SIN TARIFA PARA SU VEHICULO` | Falta la tarifa de esa garita para ese tipo de vehículo |
| `CODIGO INVALIDO` (PN532, RC522 HCE) | Token del celular expirado, alterado o ya usado |
| `ABRA LA APP Y TOQUE SU TARJETA` (PN532, RC522 HCE) | Celular con la app, pero sin activar el pago |
| `TELEFONO SIN APP` (PN532, RC522 HCE) / `CELULAR NO APTO` (RC522) | Celular que no puede pagar en ese lector |
| `ACERQUE DE NUEVO / Celular quieto` (RC522 HCE) | Se cortó la comunicación con el teléfono: mantenerlo pegado y quieto |
| `ERROR DE RED / REINTENTE PAGO` | El servidor no respondió en 5 s: la valla **no** se abre |
| `SIN SERVIDOR` | No conecta al broker MQTT (ver solución abajo) |

## 6. Problemas frecuentes

| Síntoma | Solución |
|---|---|
| `Error rc=5 (usuario/clave MQTT incorrectos)` | Revisar `GARITA_ID` y la clave MQTT contra `MQTT_DISPOSITIVOS` del `.env` |
| `Error rc=-2` repetido | IP del servidor incorrecta, o el ESP32 no está en la misma red que la Raspberry Pi |
| `ERROR LECTOR NFC` (PN532) | Revisar cables SDA/SCL y los interruptores en modo I2C |
| La LCD no muestra texto | Cambiar la dirección `0x3F` por `0x27` |
| `NO REGISTRADA` con una tarjeta conocida | Vincularla con `POST /usuario/tarjetas` |

## 7. Protocolo (referencia)

| Dirección | Topic | Payload |
|---|---|---|
| ESP32 → servidor | `peaje/<garita>/<carril>/lectura` | `{"id":"17-1234","uid":"04A1B2C3D4E580"}` |
| ESP32 → servidor | `peaje/<garita>/<carril>/token` | `{"id":"18-5678","token":"<66 caracteres hex>"}` |
| servidor → ESP32 | `peaje/<garita>/<carril>/orden` | `{"id":"17-1234","orden":"ABRIR","estado":"APROBADO","saldo":4100.0,...}` |

Pago con celular (PN532 ↔ app): el lector envía `00 A4 04 00 07 F0 50 45 41 4A 45 01 00` (SELECT AID `F05045414A4501`)
y la app responde `<token de 33 bytes> 90 00`, o `69 85` si el usuario no activó el pago.
