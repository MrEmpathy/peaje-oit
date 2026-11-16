// ============================================================
// PEAJE INTELIGENTE IoT - ESP32 + RFID RC522 + MQTT + LCD + HC-SR04
// Soporte multi-garita, MQTT con usuario/clave y respuestas con motivo del rechazo.
//
// El RC522 sólo lee el UID: sirve para tarjetas físicas vinculadas a una cuenta.
// Para pagar con el celular se necesita el PN532 (sketch peaje_pn532). Ver ../CONFIGURACION.md.
// ============================================================
#include <WiFi.h>
#include <PubSubClient.h>
#include <ESP32Servo.h>
#include <SPI.h>
#include <MFRC522.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>

// ---------------- CONFIGURACIÓN DE RED Y PEAJE ----------------
const char* ssid        = "TU_NOMBRE_WIFI";
const char* password    = "TU_PASSWORD_WIFI";
const char* mqtt_server = "192.168.1.180";  // IP de la Raspberry Pi
const int   mqtt_port   = 1883;

// Identificación de esta cabina. GARITA_ID es también el USUARIO MQTT y debe existir en la tabla garitas.
const char* GARITA_ID   = "garita_norte";
const char* CARRIL_ID   = "carril1";
// Clave MQTT de esta garita: lo que va después de "garita_norte:" en MQTT_DISPOSITIVOS del .env del servidor
const char* mqtt_password = "CLAVE_MQTT_DE_LA_GARITA";

String topic_lectura = String("peaje/") + GARITA_ID + "/" + CARRIL_ID + "/lectura";
String topic_orden   = String("peaje/") + GARITA_ID + "/" + CARRIL_ID + "/orden";

// ---------------- PINES ESP32 ----------------
#define RST_PIN    22   // RFID RST
#define SS_PIN     5    // RFID SDA/SS
#define LCD_SDA    21   // I2C SDA
#define LCD_SCL    26   // I2C SCL
#define TRIG_PIN   32   // HC-SR04 Trigger
#define ECHO_PIN   33   // HC-SR04 Echo
#define SERVO_PIN  13   // Servomotor

// ---------------- CONFIGURACIÓN FÍSICA ----------------
const int DISTANCIA_CM   = 10;   // Distancia para detectar el auto
const int ANGULO_ABIERTA = 90;
const int ANGULO_CERRADA = 0;
const int ESPERA_MAX_DS  = 80;   // 80 x 100 ms = 8 segundos máximo con la valla abierta

const unsigned long TIMEOUT_RESPUESTA_MS    = 5000;  // sin respuesta del servidor -> no se abre
const unsigned long PAUSA_MISMA_TARJETA_MS  = 3000;  // evita mandar dos veces la misma tarjeta

// ---------------- OBJETOS ----------------
WiFiClient espClient;
PubSubClient mqtt(espClient);
MFRC522 rfid(SS_PIN, RST_PIN);
Servo servo;
LiquidCrystal_I2C lcd(0x3F, 16, 2); // Si no muestra texto, cambia 0x3F por 0x27

bool esperandoRespuesta = false;
unsigned long tiempoEnvio = 0;
String idLectura = "";
unsigned long contadorLecturas = 0;
String ultimoUid = "";
unsigned long tiempoUltimoUid = 0;

// La orden llega en el callback MQTT, pero se ejecuta en loop() (mover la valla bloquea varios segundos)
bool hayOrden = false;
String ordenPendiente, estadoPendiente, saldoPendiente;

// ============================================================
// SETUP
// ============================================================
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n[SETUP] Iniciando sistema de peaje IoT...");

  // Ultrasonido
  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  digitalWrite(TRIG_PIN, LOW);
  Serial.println("[SETUP] Ultrasonido listo");

  // LCD I2C (usando pines personalizados SDA=21, SCL=26)
  Wire.begin(LCD_SDA, LCD_SCL);
  lcd.init();
  lcd.backlight();
  lcdMostrar(" SISTEMA PEAJE", " CONECTANDO...");
  Serial.println("[SETUP] LCD listo");

  // Servo (valla cerrada al iniciar)
  moverServo(ANGULO_CERRADA);
  Serial.println("[SETUP] Servo listo, valla cerrada");

  // RFID
  SPI.begin();
  rfid.PCD_Init();
  Serial.println("[SETUP] RFID listo");

  // Wi-Fi y MQTT
  conectarWiFi();
  mqtt.setServer(mqtt_server, mqtt_port);
  mqtt.setCallback(recibirOrdenMQTT);
  mqtt.setBufferSize(512);   // las respuestas JSON del servidor superan los 256 bytes por defecto con el topic
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
  if (WiFi.status() != WL_CONNECTED) {
    conectarWiFi();
  }
  if (!mqtt.connected()) {
    reconectarMQTT();
  }
  mqtt.loop();

  leerComandos();

  if (hayOrden) {
    hayOrden = false;
    if (ordenPendiente == "ABRIR") {
      accesoConcedido(estadoPendiente, saldoPendiente);
    } else {
      accesoDenegado(estadoPendiente);
    }
  }

  // Si enviamos un UID y el servidor tarda más de 5s en responder, no se abre la valla
  if (esperandoRespuesta && (millis() - tiempoEnvio > TIMEOUT_RESPUESTA_MS)) {
    Serial.println("[MQTT] Tiempo de espera agotado sin respuesta del servidor");
    esperandoRespuesta = false;
    lcdMostrar(" ERROR DE RED", "REINTENTE PAGO");
    delay(2000);
    mostrarEspera();
  }

  if (!esperandoRespuesta) {
    leerTarjeta();
  }
}

// ============================================================
// CONEXIÓN WI-FI Y MQTT
// ============================================================
void conectarWiFi() {
  Serial.print("[WIFI] Conectando a ");
  Serial.println(ssid);
  WiFi.mode(WIFI_STA);
  WiFi.begin(ssid, password);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.print("\n[WIFI] Conectado! IP: ");
  Serial.println(WiFi.localIP());
}

void reconectarMQTT() {
  while (!mqtt.connected()) {
    Serial.print("[MQTT] Conectando a Raspberry Pi...");
    String clientId = String("ESP32_") + GARITA_ID + "_" + CARRIL_ID;

    // El broker exige usuario y clave: el usuario es el código de la garita
    if (mqtt.connect(clientId.c_str(), GARITA_ID, mqtt_password)) {
      Serial.println(" Conectado!");
      mqtt.subscribe(topic_orden.c_str(), 1);
    } else {
      int rc = mqtt.state();
      Serial.print(" Error rc=");
      Serial.print(rc);
      if (rc == MQTT_CONNECT_BAD_CREDENTIALS || rc == MQTT_CONNECT_UNAUTHORIZED) {
        Serial.print(" (usuario/clave MQTT incorrectos: revisa GARITA_ID y mqtt_password)");
      }
      Serial.println(" reintentando en 2s...");
      lcdMostrar("SIN SERVIDOR", "Reintentando...");
      delay(2000);
    }
  }
}

// Callback MQTT. El servidor responde JSON:
// {"id":"...","orden":"ABRIR","estado":"APROBADO","saldo":4100.0,...}
void recibirOrdenMQTT(char* topic, byte* payload, unsigned int length) {
  String msg = "";
  for (unsigned int i = 0; i < length; i++) {
    msg += (char)payload[i];
  }
  msg.trim();
  Serial.println("[MQTT] Orden recibida: " + msg);

  String orden, estado, saldo;
  if (msg.startsWith("{")) {
    // Sólo se acepta la respuesta de la última tarjeta enviada
    if (!esperandoRespuesta || campoJson(msg, "id") != idLectura) {
      Serial.println("[MQTT] Respuesta de una lectura anterior: se ignora");
      return;
    }
    orden = campoJson(msg, "orden");
    estado = campoJson(msg, "estado");
    saldo = campoJson(msg, "saldo");
  } else {
    orden = msg;  // formato antiguo en texto plano ("ABRIR" / "DENEGAR")
  }

  esperandoRespuesta = false;
  ordenPendiente = orden;
  estadoPendiente = estado;
  saldoPendiente = saldo;
  hayOrden = true;
}

// ============================================================
// SERVO
// ============================================================
void moverServo(int angulo) {
  Serial.print("[SERVO] Moviendo a ");
  Serial.print(angulo);
  Serial.println(" grados");

  servo.attach(SERVO_PIN);
  servo.write(angulo);
  delay(600);
  servo.detach();
}

// ============================================================
// ULTRASONIDO
// ============================================================
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

// ============================================================
// RFID -> ENVÍA UID POR MQTT A LA RASPBERRY PI
// ============================================================
void leerTarjeta() {
  if (!rfid.PICC_IsNewCardPresent()) return;
  if (!rfid.PICC_ReadCardSerial()) return;

  // Convertir el UID leído a cadena hexadecimal limpia, 2 dígitos por byte (Ej: "4B8F19C2")
  String uidHex = "";
  for (byte i = 0; i < rfid.uid.size; i++) {
    if (rfid.uid.uidByte[i] < 0x10) uidHex += "0";
    uidHex += String(rfid.uid.uidByte[i], HEX);
  }
  uidHex.toUpperCase();

  // Un celular entrega un UID aleatorio de 4 bytes que empieza con 0x08: no sirve para cobrar
  bool esCelular = (rfid.uid.size == 4 && rfid.uid.uidByte[0] == 0x08);

  // Detener lectura actual para evitar rebotes
  rfid.PICC_HaltA();
  rfid.PCD_StopCrypto1();

  if (esCelular) {
    Serial.println("[RFID] Celular detectado (UID aleatorio " + uidHex + "): use una tarjeta");
    lcdMostrar("CELULAR NO APTO", "Use su tarjeta");
    delay(2000);
    mostrarEspera();
    return;
  }

  // La misma tarjeta apoyada en el lector no se envía de nuevo durante unos segundos
  if (uidHex == ultimoUid && millis() - tiempoUltimoUid < PAUSA_MISMA_TARJETA_MS) return;
  ultimoUid = uidHex;
  tiempoUltimoUid = millis();

  Serial.println("[RFID] Tarjeta detectada, UID: " + uidHex);

  // Mostrar mensaje de validación en LCD y publicar por MQTT
  lcdMostrar("  VALIDANDO...", "ID: " + uidHex);

  idLectura = String(++contadorLecturas) + "-" + String(millis() % 100000);
  String payload = "{\"id\":\"" + idLectura + "\",\"uid\":\"" + uidHex + "\"}";

  esperandoRespuesta = true;
  tiempoEnvio = millis();
  if (!mqtt.publish(topic_lectura.c_str(), payload.c_str())) {
    Serial.println("[MQTT] No se pudo publicar la lectura");
  }
}

// ============================================================
// ACCESO Y CONTROL DE BARRERA
// ============================================================
void accesoConcedido(const String& estado, const String& saldo) {
  Serial.println("[ACCESO] Cobro APROBADO (" + estado + ")");

  if (estado == "LECTURA_DUPLICADA") {
    lcdMostrar("PASO REGISTRADO", "  Buen Viaje!");
  } else if (saldo.length() > 0) {
    lcdMostrar("Gracias", "Saldo $" + String((long)saldo.toFloat()));
  } else {
    lcdMostrar("Gracias", "  Buen Viaje!");
  }

  Serial.println("[VALLA] Abriendo...");
  moverServo(ANGULO_ABIERTA);
  Serial.println("[VALLA] Abierta");

  esperarPasoAuto();

  Serial.println("[VALLA] Cerrando...");
  moverServo(ANGULO_CERRADA);
  Serial.println("[VALLA] Cerrada");

  mostrarEspera();
}

void accesoDenegado(const String& estado) {
  Serial.println("[ACCESO] Cobro DENEGADO (" + estado + ")");

  if (estado == "SALDO_INSUFICIENTE")          lcdMostrar("ACCESO DENEGADO", "SALDO INSUFIC.");
  else if (estado == "USUARIO_NO_ENCONTRADO")  lcdMostrar("NO REGISTRADA", "Vincule tarjeta");
  else if (estado == "CREDENCIAL_INACTIVA")    lcdMostrar("TARJETA INACTIVA", "");
  else if (estado == "TARIFA_NO_DEFINIDA")     lcdMostrar("SIN TARIFA PARA", "SU VEHICULO");
  else if (estado == "METODO_DESHABILITADO")   lcdMostrar("PAGO CON TARJETA", "DESHABILITADO");
  else if (estado == "ERROR_SISTEMA")          lcdMostrar("ERROR SERVIDOR", "Intente de nuevo");
  else if (estado.length() > 0)                lcdMostrar("ACCESO DENEGADO", estado);
  else                                         lcdMostrar("ACCESO DENEGADO", "SALDO / INVALIDA");

  delay(2500);
  mostrarEspera();
}

void esperarPasoAuto() {
  Serial.println("[SENSOR] Esperando vehiculo...");

  int cuenta = 0;
  long distancia = medirDistancia();

  // 1. Esperar a que llegue el auto
  while (distancia > DISTANCIA_CM && cuenta < ESPERA_MAX_DS) {
    mqtt.loop(); // Mantener viva la conexión MQTT mientras espera
    delay(100);
    cuenta++;
    distancia = medirDistancia();

    if (cuenta % 10 == 0) {
      Serial.print("[SENSOR] Distancia: ");
      Serial.print(distancia);
      Serial.println(" cm");
    }
  }

  // 2. Si llegó, esperar a que se vaya
  if (cuenta < ESPERA_MAX_DS) {
    Serial.print("[SENSOR] Vehiculo detectado a ");
    Serial.print(distancia);
    Serial.println(" cm");

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
  char c = Serial.read();
  if (c == '\n' || c == '\r' || c == ' ') return;

  c = toupper(c);

  if (c == 'A') {
    Serial.println("[CMD] Abrir valla");
    lcdMostrar("   PASO MANUAL", "  SUPERVISOR");
    moverServo(ANGULO_ABIERTA);
  }
  else if (c == 'C') {
    Serial.println("[CMD] Cerrar valla");
    moverServo(ANGULO_CERRADA);
    mostrarEspera();
  }
  else if (c == 'D') {
    long d = medirDistancia();
    Serial.print("[CMD] Distancia: ");
    Serial.print(d);
    Serial.println(" cm");
  }
  else {
    Serial.println("[CMD] Comando no valido. Use: A, C, D");
  }
}

// ============================================================
// PANTALLA
// ============================================================
void mostrarEspera() {
  lcdMostrar("PEAJE AUTOMATICO", "Acerque tarjeta");
  Serial.println("[LCD] Esperando tarjeta");
}

void lcdMostrar(const String& linea1, const String& linea2) {
  lcd.clear();
  lcd.setCursor(0, 0);
  lcd.print(linea1.substring(0, 16));
  lcd.setCursor(0, 1);
  lcd.print(linea2.substring(0, 16));
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
