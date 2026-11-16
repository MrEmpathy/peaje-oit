// ============================================================
// PEAJE INTELIGENTE IoT - ESP32 + PN532 + MQTT + LCD + HC-SR04 + SERVO
//
// Acepta dos formas de pago en el mismo lector:
//   1) Teléfono con la app Peaje IoT (NFC/HCE): el lector envía SELECT AID,
//      la app responde un token de un solo uso y se publica en .../token
//   2) Tarjeta física RFID/NFC vinculada a la cuenta: se publica su UID en .../lectura
//
// Reemplaza al RC522 (que no puede hablar con teléfonos). Ver ../CONFIGURACION.md.
// ============================================================
#include <WiFi.h>
#include <PubSubClient.h>
#include <ESP32Servo.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>
#include <PN532_I2C.h>
#include <PN532.h>

// ---------------- CONFIGURACIÓN DE RED Y PEAJE ----------------
const char* WIFI_SSID     = "TU_NOMBRE_WIFI";
const char* WIFI_PASSWORD = "TU_PASSWORD_WIFI";
const char* MQTT_SERVER   = "192.168.1.180";   // IP de la Raspberry Pi
const int   MQTT_PORT     = 1883;

// Identificación de esta cabina. GARITA_ID es también el USUARIO MQTT y debe existir en la tabla garitas.
const char* GARITA_ID     = "garita_norte";
const char* CARRIL_ID     = "carril1";
// Clave MQTT de esta garita: lo que va después de "garita_norte:" en MQTT_DISPOSITIVOS del .env del servidor
const char* MQTT_PASSWORD = "CLAVE_MQTT_DE_LA_GARITA";

// ---------------- PINES ESP32 (los mismos del firmware con RC522) ----------------
#define I2C_SDA    21   // LCD y PN532 comparten el bus I2C
#define I2C_SCL    26
#define TRIG_PIN   32   // HC-SR04 Trigger
#define ECHO_PIN   33   // HC-SR04 Echo
#define SERVO_PIN  13   // Servomotor

// ---------------- CONFIGURACIÓN FÍSICA ----------------
const int DISTANCIA_CM   = 10;   // Distancia para detectar el auto
const int ANGULO_ABIERTA = 90;
const int ANGULO_CERRADA = 0;
const int ESPERA_MAX_DS  = 80;   // 80 x 100 ms = 8 s máximo con la valla abierta

const unsigned long TIMEOUT_RESPUESTA_MS = 5000;  // sin respuesta del servidor -> no se abre
const unsigned long PAUSA_ENTRE_LECTURAS_MS = 2500;

// ---------------- PROTOCOLO CON LA APP (debe coincidir con la app Android) ----------------
// AID = F0 + "PEAJE" (ASCII) + 01   (app: res/xml/apduservice.xml y nfc/ProtocoloApdu.kt)
const uint8_t AID_PEAJE[]  = {0xF0, 0x50, 0x45, 0x41, 0x4A, 0x45, 0x01};
const uint8_t TOKEN_BYTES  = 33;      // token de POST /usuario/token-nfc (66 caracteres hex)
const uint16_t SW_OK              = 0x9000;
const uint16_t SW_SIN_PAGO_ACTIVO = 0x6985;  // la app está instalada pero el usuario no tocó "pagar"

// ---------------- OBJETOS ----------------
WiFiClient espClient;
PubSubClient mqtt(espClient);
PN532_I2C pn532i2c(Wire);
PN532 nfc(pn532i2c);
Servo servo;
LiquidCrystal_I2C lcd(0x3F, 16, 2);   // Si no muestra texto, cambia 0x3F por 0x27

String topicLectura = String("peaje/") + GARITA_ID + "/" + CARRIL_ID + "/lectura";
String topicToken   = String("peaje/") + GARITA_ID + "/" + CARRIL_ID + "/token";
String topicOrden   = String("peaje/") + GARITA_ID + "/" + CARRIL_ID + "/orden";

bool lectorListo = false;
bool esperandoRespuesta = false;
unsigned long tiempoEnvio = 0;
unsigned long ultimaLectura = 0;
String idLectura = "";
unsigned long contadorLecturas = 0;

// La orden llega en el callback MQTT, pero se ejecuta en loop() (mover la valla bloquea varios segundos).
bool hayOrden = false;
String ordenPendiente, estadoPendiente, saldoPendiente;

// ============================================================
// SETUP
// ============================================================
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n[SETUP] Iniciando peaje IoT con PN532...");

  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  digitalWrite(TRIG_PIN, LOW);

  // I2C con pines personalizados ANTES de iniciar el PN532 (así nfc.begin() no cambia los pines)
  Wire.begin(I2C_SDA, I2C_SCL);
  lcd.init();
  lcd.backlight();
  lcdMostrar(" SISTEMA PEAJE", " INICIANDO...");

  moverServo(ANGULO_CERRADA);
  iniciarLector();

  conectarWiFi();
  mqtt.setServer(MQTT_SERVER, MQTT_PORT);
  mqtt.setCallback(recibirOrdenMQTT);
  mqtt.setBufferSize(512);
  mqtt.setKeepAlive(30);
  reconectarMQTT();

  mostrarEspera();
  Serial.println("[SETUP] Sistema listo");
  Serial.println("Comandos Serial: A=abrir  C=cerrar  D=medir distancia");
}

// ============================================================
// LOOP
// ============================================================
void loop() {
  if (WiFi.status() != WL_CONNECTED) conectarWiFi();
  if (!mqtt.connected()) reconectarMQTT();
  mqtt.loop();

  leerComandos();

  if (hayOrden) {
    hayOrden = false;
    if (ordenPendiente == "ABRIR") accesoConcedido(estadoPendiente, saldoPendiente);
    else accesoDenegado(estadoPendiente);
  }

  if (esperandoRespuesta && millis() - tiempoEnvio > TIMEOUT_RESPUESTA_MS) {
    Serial.println("[MQTT] Sin respuesta del servidor: la valla NO se abre");
    esperandoRespuesta = false;
    lcdMostrar(" ERROR DE RED", "REINTENTE PAGO");
    delay(2000);
    mostrarEspera();
  }

  if (!lectorListo) {
    static unsigned long ultimoIntento = 0;
    if (millis() - ultimoIntento > 5000) { ultimoIntento = millis(); iniciarLector(); if (lectorListo) mostrarEspera(); }
    return;
  }

  if (!esperandoRespuesta && millis() - ultimaLectura > PAUSA_ENTRE_LECTURAS_MS) {
    leerNfc();
  }
}

// ============================================================
// LECTOR PN532
// ============================================================
void iniciarLector() {
  nfc.begin();
  uint32_t version = nfc.getFirmwareVersion();
  if (!version) {
    lectorListo = false;
    Serial.println("[NFC] No se encontro el PN532. Revisa cables y que los switches esten en modo I2C.");
    lcdMostrar("ERROR LECTOR NFC", "Revise PN532");
    return;
  }
  Serial.printf("[NFC] PN5%02X firmware %u.%u\n",
                (unsigned)((version >> 24) & 0xFF), (unsigned)((version >> 16) & 0xFF), (unsigned)((version >> 8) & 0xFF));
  nfc.setPassiveActivationRetries(0x05);  // no quedarse bloqueado esperando una tarjeta
  nfc.SAMConfig();
  lectorListo = true;
}

void leerNfc() {
  uint8_t uid[10];
  uint8_t uidLen = 0;
  // inlist = true: deja el objetivo seleccionado para poder enviarle APDUs
  if (!nfc.readPassiveTargetID(PN532_MIFARE_ISO14443A, uid, &uidLen, 100, true)) return;
  ultimaLectura = millis();

  // 1) ¿Es un teléfono con la app de peaje? -> SELECT AID
  uint8_t apdu[5 + sizeof(AID_PEAJE) + 1];
  apdu[0] = 0x00; apdu[1] = 0xA4; apdu[2] = 0x04; apdu[3] = 0x00; apdu[4] = sizeof(AID_PEAJE);
  memcpy(apdu + 5, AID_PEAJE, sizeof(AID_PEAJE));
  apdu[sizeof(apdu) - 1] = 0x00;  // Le

  uint8_t resp[64];
  uint8_t respLen = sizeof(resp);
  bool esIsoDep = nfc.inDataExchange(apdu, sizeof(apdu), resp, &respLen);

  if (esIsoDep && respLen >= 2) {
    uint16_t sw = (resp[respLen - 2] << 8) | resp[respLen - 1];
    if (sw == SW_OK && respLen - 2 == TOKEN_BYTES) {
      Serial.println("[NFC] Telefono con la app: token recibido");
      enviarLectura(true, aHex(resp, TOKEN_BYTES));
      return;
    }
    if (sw == SW_SIN_PAGO_ACTIVO) {
      Serial.println("[NFC] App detectada, pero el pago no esta activo");
      lcdMostrar("ABRA LA APP Y", "TOQUE SU TARJETA");
      delay(2000);
      mostrarEspera();
      return;
    }
    Serial.printf("[NFC] Dispositivo ISO-DEP sin la app (SW=%04X)\n", sw);
  }

  // 2) Tarjeta física -> cobro por UID.
  //    Los teléfonos usan un UID aleatorio de 4 bytes que empieza con 0x08: no sirve para cobrar.
  if (uidLen == 4 && uid[0] == 0x08) {
    Serial.println("[NFC] Telefono sin la app de peaje (UID aleatorio)");
    lcdMostrar("TELEFONO SIN APP", "Use app Peaje");
    delay(2000);
    mostrarEspera();
    return;
  }
  String uidHex = aHex(uid, uidLen);
  Serial.println("[NFC] Tarjeta UID: " + uidHex);
  enviarLectura(false, uidHex);
}

// ============================================================
// MQTT
// ============================================================
void enviarLectura(bool esToken, const String& valor) {
  idLectura = String(++contadorLecturas) + "-" + String(millis() % 100000);
  String payload = String("{\"id\":\"") + idLectura + "\",\"" + (esToken ? "token" : "uid") + "\":\"" + valor + "\"}";
  const String& topic = esToken ? topicToken : topicLectura;

  lcdMostrar("  VALIDANDO...", esToken ? "Pago con celular" : ("ID: " + valor));
  esperandoRespuesta = true;
  tiempoEnvio = millis();
  if (!mqtt.publish(topic.c_str(), payload.c_str())) {
    Serial.println("[MQTT] No se pudo publicar la lectura");
  } else {
    Serial.println("[MQTT] -> " + topic + " " + payload);
  }
}

void recibirOrdenMQTT(char* topic, byte* payload, unsigned int length) {
  String msg;
  msg.reserve(length);
  for (unsigned int i = 0; i < length; i++) msg += (char)payload[i];
  msg.trim();
  Serial.println("[MQTT] <- " + msg);

  String orden, estado, saldo;
  if (msg.startsWith("{")) {
    // Respuesta JSON: sólo se acepta la que corresponde a la última lectura enviada
    if (!esperandoRespuesta || campoJson(msg, "id") != idLectura) {
      Serial.println("[MQTT] Respuesta de una lectura anterior: se ignora");
      return;
    }
    orden = campoJson(msg, "orden");
    estado = campoJson(msg, "estado");
    saldo = campoJson(msg, "saldo");
  } else {
    orden = msg;  // formato antiguo en texto plano
  }

  esperandoRespuesta = false;
  ordenPendiente = orden;
  estadoPendiente = estado;
  saldoPendiente = saldo;
  hayOrden = true;
}

void conectarWiFi() {
  Serial.print("[WIFI] Conectando a ");
  Serial.println(WIFI_SSID);
  lcdMostrar(" CONECTANDO", " WIFI...");
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.print("\n[WIFI] Conectado! IP: ");
  Serial.println(WiFi.localIP());
}

void reconectarMQTT() {
  while (!mqtt.connected()) {
    Serial.print("[MQTT] Conectando a la Raspberry Pi...");
    String clientId = String("ESP32_") + GARITA_ID + "_" + CARRIL_ID;
    if (mqtt.connect(clientId.c_str(), GARITA_ID, MQTT_PASSWORD)) {
      Serial.println(" Conectado!");
      mqtt.subscribe(topicOrden.c_str(), 1);
    } else {
      int rc = mqtt.state();
      Serial.printf(" Error rc=%d%s, reintentando en 2s...\n", rc,
                    rc == MQTT_CONNECT_BAD_CREDENTIALS || rc == MQTT_CONNECT_UNAUTHORIZED ? " (usuario/clave MQTT incorrectos)" : "");
      lcdMostrar("SIN SERVIDOR", "Reintentando...");
      delay(2000);
    }
  }
}

// ============================================================
// ACCESO Y CONTROL DE BARRERA
// ============================================================
void accesoConcedido(const String& estado, const String& saldo) {
  Serial.println("[ACCESO] APROBADO (" + estado + ")");
  if (estado == "LECTURA_DUPLICADA") lcdMostrar("PASO REGISTRADO", "  Buen viaje!");
  else if (saldo.length()) lcdMostrar("PAGO APROBADO", "Saldo $" + String((long)saldo.toFloat()));
  else lcdMostrar("Gracias", "  Buen Viaje!");

  moverServo(ANGULO_ABIERTA);
  esperarPasoAuto();
  moverServo(ANGULO_CERRADA);
  mostrarEspera();
}

void accesoDenegado(const String& estado) {
  Serial.println("[ACCESO] DENEGADO (" + estado + ")");
  if (estado == "SALDO_INSUFICIENTE")             lcdMostrar("SALDO INSUFIC.", "Recargue en app");
  else if (estado == "USUARIO_NO_ENCONTRADO")     lcdMostrar("NO REGISTRADA", "Vincule tarjeta");
  else if (estado == "CREDENCIAL_INACTIVA")       lcdMostrar("TARJETA INACTIVA", "");
  else if (estado.startsWith("TOKEN_"))           lcdMostrar("CODIGO INVALIDO", "Reintente pago");
  else if (estado == "TARIFA_NO_DEFINIDA")        lcdMostrar("SIN TARIFA PARA", "SU VEHICULO");
  else if (estado == "METODO_DESHABILITADO")      lcdMostrar("PAGO CON TARJETA", "DESHABILITADO");
  else if (estado == "ERROR_SISTEMA")             lcdMostrar("ERROR SERVIDOR", "Intente de nuevo");
  else                                            lcdMostrar("ACCESO DENEGADO", estado);
  delay(2500);
  mostrarEspera();
}

void moverServo(int angulo) {
  servo.attach(SERVO_PIN);
  servo.write(angulo);
  delay(600);
  servo.detach();
}

long medirDistancia() {
  digitalWrite(TRIG_PIN, LOW);
  delayMicroseconds(2);
  digitalWrite(TRIG_PIN, HIGH);
  delayMicroseconds(10);
  digitalWrite(TRIG_PIN, LOW);
  long duracion = pulseIn(ECHO_PIN, HIGH, 30000);
  if (duracion == 0) return 999;
  return duracion * 0.034 / 2;
}

void esperarPasoAuto() {
  int cuenta = 0;
  long distancia = medirDistancia();
  // 1. Esperar a que llegue el auto
  while (distancia > DISTANCIA_CM && cuenta < ESPERA_MAX_DS) {
    mqtt.loop();
    delay(100);
    cuenta++;
    distancia = medirDistancia();
  }
  // 2. Si llegó, esperar a que se vaya
  if (cuenta < ESPERA_MAX_DS) {
    Serial.printf("[SENSOR] Vehiculo detectado a %ld cm\n", distancia);
    while (medirDistancia() <= DISTANCIA_CM) {
      mqtt.loop();
      delay(50);
    }
    Serial.println("[SENSOR] Vehiculo ya paso");
    delay(800);
  } else {
    Serial.println("[SENSOR] Tiempo agotado, no se detecto vehiculo");
  }
}

// ============================================================
// COMANDOS DEL SUPERVISOR POR MONITOR SERIE (115200 baud)
// ============================================================
void leerComandos() {
  if (!Serial.available()) return;
  char c = toupper(Serial.read());
  if (c == '\n' || c == '\r' || c == ' ') return;
  if (c == 'A') {
    Serial.println("[CMD] Abrir valla");
    lcdMostrar("  PASO MANUAL", "  SUPERVISOR");
    moverServo(ANGULO_ABIERTA);
  } else if (c == 'C') {
    Serial.println("[CMD] Cerrar valla");
    moverServo(ANGULO_CERRADA);
    mostrarEspera();
  } else if (c == 'D') {
    Serial.printf("[CMD] Distancia: %ld cm\n", medirDistancia());
  } else {
    Serial.println("[CMD] Comando no valido. Use: A, C, D");
  }
}

// ============================================================
// UTILIDADES
// ============================================================
void mostrarEspera() {
  lcdMostrar("PEAJE AUTOMATICO", "Tarjeta/Celular");
}

void lcdMostrar(const String& linea1, const String& linea2) {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(linea1.substring(0, 16));
  lcd.setCursor(0, 1);
  lcd.print(linea2.substring(0, 16));
}

// Bytes -> hexadecimal en mayúsculas, siempre 2 dígitos por byte (0A, no A)
String aHex(const uint8_t* datos, uint8_t largo) {
  static const char HEX_CHARS[] = "0123456789ABCDEF";
  String s;
  s.reserve(largo * 2);
  for (uint8_t i = 0; i < largo; i++) {
    s += HEX_CHARS[datos[i] >> 4];
    s += HEX_CHARS[datos[i] & 0x0F];
  }
  return s;
}

// Extrae el valor de "clave" de un JSON simple y plano (sin librerías). Devuelve "" si no está o es null.
String campoJson(const String& json, const char* clave) {
  String patron = String("\"") + clave + "\"";
  int i = json.indexOf(patron);
  if (i < 0) return "";
  i = json.indexOf(':', i + patron.length());
  if (i < 0) return "";
  i++;
  while (i < (int)json.length() && json[i] == ' ') i++;
  if (i >= (int)json.length()) return "";
  if (json[i] == '"') {
    int fin = json.indexOf('"', i + 1);
    return fin < 0 ? "" : json.substring(i + 1, fin);
  }
  int fin = i;
  while (fin < (int)json.length() && json[fin] != ',' && json[fin] != '}') fin++;
  String valor = json.substring(i, fin);
  valor.trim();
  return valor == "null" ? "" : valor;
}
