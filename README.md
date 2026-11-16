# Peaje IoT

Telepeaje con barrera automática: el conductor paga acercando una tarjeta RFID o su celular (NFC) al lector;
el servidor descuenta la tarifa según el tipo de vehículo y ordena abrir la barrera.

```
App Android ──HTTP + JWT──► API REST (FastAPI) ──┐
     │                                            ├──► MySQL / MariaDB
     └──NFC (token)──► ESP32 + lector ──MQTT──► Mosquitto ◄──► worker MQTT
                          │
                    Servo / LCD / HC-SR04
```

## Contenido

| Carpeta | Qué es | Guía |
|---|---|---|
| `backend/` | API REST, worker MQTT, broker Mosquitto (Docker Compose) y esquema de la base de datos | `backend/README.md` |
| `app/` | App Android (Kotlin + Jetpack Compose): login, saldo, recargas, cuenta y pago NFC | abajo |
| `firmware/` | Firmwares del ESP32: `peaje_rc522` (tarjetas) y `peaje_pn532` (tarjetas + celular) | `firmware/CONFIGURACION.md` |

## Puesta en marcha

### 1. Backend (server)

```bash
cd backend
# backend/.env ya trae la configuración real (claves de BD, JWT, tokens NFC y MQTT).
# Para una instalación nueva se puede partir de .env.example.
mysql -u root -p < db/schema.sql  # crea la base de datos (¡borra la existente!)
docker compose up -d --build
```

> `backend/.env` contiene claves reales: no subir esta carpeta a un repositorio público ni compartirla tal cual.

### 2. App Android

1. Abrir la carpeta `app/` con Android Studio (*Open*). Genera `local.properties` con la ruta del SDK.
2. Si el servidor no está en `192.168.1.191`, ver [Si cambia la IP del server](#si-cambia-la-ip-del-server).
3. Ejecutar en un teléfono conectado a la misma red Wi-Fi que el servidor.

Detalle paso a paso (Android Studio, consola y adb): [Compilar e instalar la app](#compilar-e-instalar-la-app).

### 3. Firmware (ESP32)

Ver `firmware/CONFIGURACION.md`: elegir el firmware según el lector, completar Wi-Fi y clave MQTT, y subirlo.

## Compilar e instalar la app

### Opción 1: Android Studio

1. **File → Open** → elegir la carpeta `app/`. La primera vez crea `local.properties` con la ruta del SDK y descarga lo necesario.
2. Conectar el teléfono por USB con la depuración USB activada (paso 3 de la opción 2); debe aparecer en la lista de dispositivos de la barra superior.
3. Presionar **Run ▶**: compila, instala y abre la app.
   Para generar sólo el APK: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.

### Opción 2: Por consola (PowerShell en Windows)

**Requisitos:** JDK 17 o superior y el Android SDK (se instala con Android Studio).

#### 1. Preparar (una sola vez)

Crear `app/local.properties` con la ruta del Android SDK de ese PC (no se incluye en el proyecto porque cambia en cada equipo):

```properties
sdk.dir=C\:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk
```

#### 2. Compilar

```powershell
cd <ruta>\PeajeIoT\app
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"   # ruta de tu JDK
.\gradlew.bat assembleDebug
```

- El APK queda en `app\app\build\outputs\apk\debug\app-debug.apk`.
- La primera vez tarda unos minutos (descarga Gradle y librerías); después, segundos.
- Este paso es el que graba en el APK la IP configurada en `gradle.properties`.
- Para correr los tests unitarios: `.\gradlew.bat testDebugUnitTest`.

#### 3. Activar la depuración USB (una sola vez)

1. En el teléfono: **Ajustes → Acerca del teléfono → Información de software** → tocar **7 veces "Número de compilación"**.
2. **Ajustes → Opciones de desarrollador → Depuración USB** → activar.
3. Al conectar el cable, aceptar **"¿Permitir depuración USB?"** (marcar *Permitir siempre desde este equipo*).

#### 4. Instalar con adb

`adb` viene con el SDK, pero no está en el PATH:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

& $adb devices                     # debe decir "device"; "unauthorized" = falta aceptar el aviso en el teléfono
& $adb install -r --user 0 app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.peaje.app/.MainActivity     # abrir la app
```

- `-r` reemplaza la versión instalada **conservando los datos** (la sesión sigue iniciada).
- `--user 0` instala sólo en el perfil principal. Sin esa opción, en teléfonos Samsung con *Dual App* o
  *Carpeta segura* la app se instala también en esos perfiles y aparece **dos veces**.
- Atajo: `.\gradlew.bat installDebug` compila e instala en un solo paso (después se abre la app a mano),
  pero instala en todos los perfiles; si aparece duplicada, ver *Errores comunes*.

### Instalar sin cable (Wi-Fi, Android 11 o superior)

1. En **Opciones de desarrollador → Depuración inalámbrica** → activar → **"Vincular dispositivo con código"**.
2. Vincular y conectar (IP y puertos se ven en esa misma pantalla del teléfono):

```powershell
& $adb pair <ip-del-telefono>:<puerto-de-vinculacion>     # pide el código de 6 dígitos que muestra el teléfono
& $adb connect <ip-del-telefono>:<puerto-de-conexion>
```

Luego `adb install` funciona igual que por cable.

### Errores comunes

| Error | Solución |
|---|---|
| `unauthorized` en `adb devices` | Desbloquear el teléfono y aceptar el aviso. Si no aparece: Opciones de desarrollador → *Revocar autorizaciones de depuración USB* y reconectar |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | La app instalada se compiló en otro PC (otra firma). Desinstalar con `& $adb uninstall com.peaje.app` y volver a instalar (se cierra la sesión) |
| `more than one device/emulator` | Indicar el dispositivo: `& $adb -s <serie> install -r ...` (la serie aparece en `adb devices`) |
| La app aparece **dos veces** en el teléfono | Quedó instalada también en el perfil *Dual App* (Samsung). Ver los perfiles con `& $adb shell pm list users` y quitarla del que no corresponde: `& $adb shell pm uninstall --user 95 com.peaje.app` (95 = Dual App; no afecta a la app principal) |
| `SDK location not found` | Falta `app/local.properties` (paso 1) |
| `JAVA_HOME is not set` | Ejecutar la línea `$env:JAVA_HOME = ...` en la misma ventana de PowerShell |

Para ver los registros de la app mientras se usa (útil para el pago NFC):

```powershell
& $adb logcat -s PeajeHce okhttp.OkHttpClient AndroidRuntime
```

## Si cambia la IP del server

El backend no cambia (escucha en todas sus interfaces). Hay que actualizar **la app** y **el firmware**.

Para saber la IP nueva, en el server: `hostname -I`.

### App (endpoint de la API)

Son dos archivos; en ambos va la misma IP:

1. **`app/gradle.properties`** — dirección de la API (debe terminar en `/`):

   ```properties
   peaje.apiUrl=http://192.168.1.50:8000/
   ```

2. **`app/app/src/main/res/xml/network_security_config.xml`** — Android bloquea HTTP sin cifrar;
   aquí se permite sólo hacia la IP del server:

   ```xml
   <domain includeSubdomains="false">192.168.1.50</domain>
   ```

   Si falta este cambio, la app muestra *"No se pudo conectar con el servidor"* aunque la IP del paso 1 sea correcta.

3. Volver a compilar e instalar la app (ver [Compilar e instalar la app](#compilar-e-instalar-la-app)).
   La IP queda grabada en el APK, así que un teléfono con la versión anterior sigue apuntando a la IP vieja.

**Usando un dominio HTTPS** (por ejemplo el túnel de Cloudflare de la arquitectura): basta con el paso 1
(`peaje.apiUrl=https://peaje.midominio.cl/`); el paso 2 no hace falta porque el tráfico ya va cifrado.
Así la app funciona también con datos móviles y no depende de la IP local.

Para comprobar la dirección antes de compilar, abrir en el navegador del teléfono `http://<ip>:8000/salud`;
debe responder `{"estado":"ok"}`.

### Firmware (ESP32)

En el `.ino` que esté usando el ESP32, cambiar la IP del broker MQTT y volver a subirlo:

- `firmware/peaje_rc522/peaje_rc522.ino` → `const char* mqtt_server = "192.168.1.50";`
- `firmware/peaje_pn532/peaje_pn532.ino` → `const char* MQTT_SERVER = "192.168.1.50";`

### Evitar que la IP cambie

Lo más simple es reservar la IP del server en el router (*DHCP → reserva de dirección*, asociándola a su MAC;
la MAC se ve con `ip link` en el server). Así no hay que recompilar la app ni el firmware.

## Formas de pago

| Forma | Lector | Cómo |
|---|---|---|
| Tarjeta física | RC522 o PN532 | Tarjeta vinculada a la cuenta (`POST /usuario/tarjetas`); se cobra por su UID |
| Celular (NFC) | PN532 | En la app, tocar la tarjeta azul de Inicio y acercar el teléfono; se cobra con un token de un solo uso |
