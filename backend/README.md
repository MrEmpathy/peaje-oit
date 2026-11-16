# Peaje IoT – Backend

Servicios (`docker-compose.yml`, todos con `network_mode: host`):

| Servicio | Contenedor | Función |
|---|---|---|
| `mosquitto` | `peaje_mqtt` | Broker MQTT en el puerto 1883, con usuario/clave y ACL |
| `api_fastapi` | `peaje_api` | API REST para la app Android (puerto 8000) |
| `worker_mqtt` | `peaje_worker` | Procesa las lecturas de los ESP32 y cobra |

```bash
mysql -u root -p < db/schema.sql      # SOLO la primera vez: borra y recrea la BD
docker compose up -d --build --remove-orphans
./live_log.sh
```

## Tarifas por tipo de vehículo

Cada usuario registra **una patente y un tipo de vehículo** (`MOTO`, `AUTO`, `CAMIONETA`, `BUS`, `CAMION`).
El monto se toma de la tabla `tarifas` según **garita + tipo de vehículo**. Si falta esa combinación,
el paso se deniega con estado `TARIFA_NO_DEFINIDA`.

```sql
-- Cambiar un monto
UPDATE tarifas t JOIN garitas g ON g.id = t.garita_id JOIN tipos_vehiculo tv ON tv.id = t.tipo_vehiculo_id
SET t.monto = 1000 WHERE g.codigo = 'garita_norte' AND tv.codigo = 'AUTO';

-- Nueva garita con sus tarifas
INSERT INTO garitas (codigo, nombre) VALUES ('garita_sur', 'Garita Sur');
INSERT INTO tarifas (garita_id, tipo_vehiculo_id, monto)
SELECT g.id, tv.id, 900 FROM garitas g CROSS JOIN tipos_vehiculo tv WHERE g.codigo = 'garita_sur';
```

## Métodos de cobro

| | Opción 1: Token HCE | Opción 2: UID |
|---|---|---|
| Lector | PN532 (ISO 14443-4 / APDU) | RC522 o PN532 |
| Qué se lee | Token firmado de un solo uso entregado por la app | UID de tarjeta física o UID virtual |
| Topic | `peaje/<garita>/<carril>/token` | `peaje/<garita>/<carril>/lectura` |
| Seguridad | Alta (firma HMAC, expira, no reutilizable) | Baja (un UID se puede copiar) |

La opción 2 se puede desactivar con `PERMITIR_COBRO_UID=false`.

> Un teléfono Android en modo HCE **no** expone un UID fijo (cambia en cada acercamiento),
> por eso con el RC522 el teléfono no sirve como credencial: usa tarjetas físicas vinculadas.

## Protocolo MQTT (ESP32)

Usuario MQTT del ESP32 = **código de la garita** (ver `MQTT_DISPOSITIVOS` en `.env`).
Cada ESP32 sólo puede publicar y leer los topics de su propia garita.

**Publica** en `peaje/<garita>/<carril>/lectura` (o `/token`), QoS 1:

```text
A1B2C3D4                                   <- texto plano (compatible con el firmware actual)
{"id":"17","uid":"A1B2C3D4"}               <- JSON (recomendado)
{"id":"18","token":"01000000..."}          <- JSON, token HCE (66 caracteres hex)
```

**Se suscribe** a `peaje/<garita>/<carril>/orden`. La respuesta usa el mismo formato que la petición:

```text
ABRIR | DENEGAR
{"id":"17","orden":"ABRIR","estado":"APROBADO","saldo":4100.0,"tarifa":900.0,"patente":"BCDF12","tipo_vehiculo":"AUTO"}
```

`estado` puede ser: `APROBADO`, `LECTURA_DUPLICADA` (abre sin cobrar), `SALDO_INSUFICIENTE`,
`USUARIO_NO_ENCONTRADO`, `CREDENCIAL_INACTIVA`, `TRANSACCION_INVALIDA`, `METODO_DESHABILITADO`,
`TARIFA_NO_DEFINIDA`, `TOKEN_INVALIDO`, `TOKEN_EXPIRADO`, `TOKEN_REUTILIZADO`, `ERROR_SISTEMA`.

Recomendaciones para el firmware:
- Enviar el UID **con todos los bytes a 2 dígitos** (`0A`, no `A`); si no, no coincidirá con el registrado.
- Si no llega `orden` en ~3 s, no abrir (falla segura).
- No volver a enviar la misma tarjeta durante unos segundos.

## Token HCE (opción 1)

1. En la app el usuario toca la tarjeta de saldo ("Toca para pagar con NFC"). La app pide
   `POST /usuario/token-nfc` → `{"token": "<66 hex>", "valido_por_segundos": 300, ...}` y lo renueva sola antes de expirar.
2. Mientras esa pantalla está abierta, el `HostApduService` de la app responde al lector:

   | Lector → app | App → lector |
   |---|---|
   | `00 A4 04 00 07 F0 50 45 41 4A 45 01 00` (SELECT AID `F05045414A4501`) | 33 bytes del token + `90 00` |
   | mismo SELECT, pero el usuario no activó el pago | `69 85` |
   | otro AID | `6A 82` |

3. El ESP32 (firmware `peaje_pn532`) publica el token en hex en `.../token`. El worker valida firma, vigencia y que no se haya usado.
4. La app consulta `GET /usuario/historial` hasta ver la transacción nueva y muestra el resultado.
   Tras un cobro aprobado el token queda consumido: el siguiente pago usa uno nuevo.

Formato (33 bytes): `versión(1) | usuario_id(4) | expira_unix(4) | nonce(8) | HMAC-SHA256[:16]`.

## API REST

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/tipos-vehiculo` | Lista de tipos para el selector del registro |
| GET | `/tarifas?garita=garita_norte` | Tarifas por garita y tipo (parámetro opcional) |
| POST | `/registro` | Crea usuario + UID virtual (límite 3/min por IP). Ver ejemplo abajo |
| POST | `/login` | Devuelve JWT (límite 5/min por IP) |
| GET | `/usuario/saldo` | Datos, vehículo y saldo |
| PUT | `/usuario/vehiculo` | Cambia patente / tipo `{"patente": "BCDF12", "tipo_vehiculo": "AUTO"}` |
| POST | `/usuario/recargar` | `{"monto": 5000}` (máx. `RECARGA_MAXIMA`) |
| GET | `/usuario/historial?limite=50&offset=0` | Pasos por peaje |
| GET | `/usuario/recargas` | Historial de recargas |
| POST | `/usuario/token-nfc` | Token HCE de un solo uso |
| GET | `/usuario/tarjetas` | Credenciales del usuario |
| POST | `/usuario/tarjetas` | Vincula tarjeta física `{"uid": "04A1B2C3D4E580", "alias": "Llavero"}` |
| DELETE | `/usuario/tarjetas/{id}` | Desvincula una tarjeta física |
| GET | `/salud` | Estado de la API y la BD |

Registro:

```json
POST /registro
{"nombre": "Ana", "apellido": "Paz", "correo": "ana@mail.cl", "password": "clave1234",
 "patente": "BC-DF-12", "tipo_vehiculo": "AUTO"}
```

La patente se guarda normalizada (`BCDF12`): 5 a 8 letras y números, con al menos una letra y un número,
y no puede repetirse entre cuentas. Las respuestas de usuario incluyen `patente`, `tipo_vehiculo`
y `tipo_vehiculo_nombre`.

Para vincular una tarjeta física, la app puede leer su UID con el propio NFC del teléfono
(`Tag.getId()` en modo lector) y enviarlo a `POST /usuario/tarjetas`.
