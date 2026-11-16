// ============================================================
// PEAJE INTELIGENTE IoT - ESP32 + RC522 (con pago por celular) + MQTT + LCD + HC-SR04 + SERVO
//
// Igual que peaje_rc522, pero además acepta el pago con el teléfono (app Peaje IoT, NFC/HCE),
// como el firmware peaje_pn532:
//   1) Teléfono con la app: el lector activa ISO 14443-4 (RATS), envía SELECT AID,
//      la app responde un token de un solo uso y se publica en .../token
//   2) Tarjeta física RFID vinculada a la cuenta: se publica su UID en .../lectura
//
// La librería MFRC522 sólo trae el nivel ISO 14443-3 (UID, MIFARE). El protocolo ISO 14443-4
// (ISO-DEP: RATS, bloques I/R/S, WTX, DESELECT) está implementado más abajo, en "ISO-DEP",
// hablando directo con los registros del RC522. Mismo cableado que peaje_rc522. Ver ../CONFIGURACION.md.
// ============================================================
#include <WiFi.h>
#include <PubSubClient.h>
#include <ESP32Servo.h>
#include <SPI.h>
#include <MFRC522.h>
#include <Wire.h>
#include <LiquidCrystal_I2C.h>

// ---------------- CONFIGURACIÓN DE RED Y PEAJE ----------------
const char* WIFI_SSID     = "TU_NOMBRE_WIFI";
const char* WIFI_PASSWORD = "TU_PASSWORD_WIFI";
const char* MQTT_SERVER   = "192.168.1.180";   // IP del servidor (backend)
const int   MQTT_PORT     = 1883;

// Identificación de esta cabina. GARITA_ID es también el USUARIO MQTT y debe existir en la tabla garitas.
const char* GARITA_ID     = "garita_norte";
const char* CARRIL_ID     = "carril1";
// Clave MQTT de esta garita: lo que va después de "garita_norte:" en MQTT_DISPOSITIVOS del .env del servidor
const char* MQTT_PASSWORD = "CLAVE_MQTT_DE_LA_GARITA";

// ---------------- PINES ESP32 (los mismos de peaje_rc522) ----------------
#define RST_PIN    22   // RFID RST
#define SS_PIN     5    // RFID SDA/SS   (SCK=18, MOSI=23, MISO=19)
#define LCD_SDA    21   // I2C SDA
#define LCD_SCL    26   // I2C SCL
#define TRIG_PIN   32   // HC-SR04 Trigger
#define ECHO_PIN   33   // HC-SR04 Echo
#define SERVO_PIN  13   // Servomotor

// ---------------- CONFIGURACIÓN FÍSICA ----------------
const int DISTANCIA_CM   = 10;   // Distancia para detectar el auto
const int ANGULO_ABIERTA = 90;
const int ANGULO_CERRADA = 0;
const int ESPERA_MAX_DS  = 80;   // 80 x 100 ms = 8 s máximo con la valla abierta

const unsigned long TIMEOUT_RESPUESTA_MS   = 5000;  // sin respuesta del servidor -> no se abre
const unsigned long PAUSA_MISMA_TARJETA_MS = 3000;  // evita mandar dos veces la misma tarjeta
const unsigned long PAUSA_CELULAR_MS       = 2500;  // tras atender un teléfono

// ---------------- PROTOCOLO CON LA APP (debe coincidir con la app Android) ----------------
// AID = F0 + "PEAJE" (ASCII) + 01   (app: res/xml/apduservice.xml y nfc/ProtocoloApdu.kt)
const uint8_t AID_PEAJE[]  = {0xF0, 0x50, 0x45, 0x41, 0x4A, 0x45, 0x01};
const uint8_t TOKEN_BYTES  = 33;      // token de POST /usuario/token-nfc (66 caracteres hex)
const uint16_t SW_OK              = 0x9000;
const uint16_t SW_SIN_PAGO_ACTIVO = 0x6985;  // la app está instalada pero el usuario no tocó "pagar"

// ---------------- ISO-DEP: parámetros ----------------
// FSD = 64 bytes (FSDI=5): coincide con la FIFO de 64 bytes del RC522, así ninguna trama la desborda.
const uint8_t  ISO_FSDI           = 5;
const uint8_t  ISO_FIFO           = 64;
const uint16_t ISO_MARGEN_MS      = 60;    // margen sobre el FWT anunciado por el teléfono
const uint16_t ISO_TIMEOUT_MAX_MS = 1600;  // máximo del timer del RC522 (65535 x 25 us)
const uint8_t  ISO_MAX_WTX        = 20;    // máximo de pedidos de "más tiempo" del teléfono

// ---------------- OBJETOS ----------------
WiFiClient espClient;
PubSubClient mqtt(espClient);
MFRC522 rfid(SS_PIN, RST_PIN);
Servo servo;
LiquidCrystal_I2C lcd(0x3F, 16, 2);   // Si no muestra texto, cambia 0x3F por 0x27

String topicLectura = String("peaje/") + GARITA_ID + "/" + CARRIL_ID + "/lectura";
String topicToken   = String("peaje/") + GARITA_ID + "/" + CARRIL_ID + "/token";
String topicOrden   = String("peaje/") + GARITA_ID + "/" + CARRIL_ID + "/orden";

bool esperandoRespuesta = false;
unsigned long tiempoEnvio = 0;
String idLectura = "";
unsigned long contadorLecturas = 0;
String ultimoUid = "";
unsigned long tiempoUltimoUid = 0;
unsigned long tiempoUltimoCelular = 0;
bool hayCelularReciente = false;

// La orden llega en el callback MQTT, pero se ejecuta en loop() (mover la valla bloquea varios segundos)
bool hayOrden = false;
String ordenPendiente, estadoPendiente, saldoPendiente;

// Estado de la sesión ISO-DEP en curso
uint8_t  isoBloque = 0;      // número de bloque del lector (0/1, alterna)
uint16_t isoFsc    = 16;     // trama máxima que acepta el teléfono (bytes, con PCB y CRC)
uint16_t isoFwtMs  = 5;      // tiempo de espera de trama anunciado por el teléfono

enum ResultadoCelular { CEL_TOKEN, CEL_SIN_PAGO, CEL_SIN_APP, CEL_ERROR };

// ============================================================
// SETUP
// ============================================================
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n[SETUP] Iniciando peaje IoT con RC522 (tarjetas + celular)...");

  pinMode(TRIG_PIN, OUTPUT);
  pinMode(ECHO_PIN, INPUT);
  digitalWrite(TRIG_PIN, LOW);

  Wire.begin(LCD_SDA, LCD_SCL);
  lcd.init();
  lcd.backlight();
  lcdMostrar(" SISTEMA PEAJE", " INICIANDO...");

  moverServo(ANGULO_CERRADA);

  SPI.begin();
  rfid.PCD_Init();
  // Ganancia máxima del receptor: la señal de carga de un teléfono es más débil que la de una tarjeta
  rfid.PCD_SetAntennaGain(MFRC522::RxGain_max);
  rfid.PCD_DumpVersionToSerial();

  conectarWiFi();
  mqtt.setServer(MQTT_SERVER, MQTT_PORT);
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

  if (hayCelularReciente && millis() - tiempoUltimoCelular < PAUSA_CELULAR_MS) return;
  hayCelularReciente = false;

  if (!esperandoRespuesta) leerTarjeta();
}

// ============================================================
// LECTURA: CELULAR (TOKEN) O TARJETA (UID)
// ============================================================
void leerTarjeta() {
  if (!rfid.PICC_IsNewCardPresent()) return;
  if (!rfid.PICC_ReadCardSerial()) return;

  String uidHex = aHex(rfid.uid.uidByte, rfid.uid.size);
  // Los teléfonos usan un UID aleatorio de 4 bytes que empieza con 0x08: no sirve para cobrar por UID
  bool uidAleatorio = (rfid.uid.size == 4 && rfid.uid.uidByte[0] == 0x08);
  // SAK bit 6 (0x20) = el objetivo habla ISO 14443-4 (teléfono en HCE, DESFire, etc.)
  bool esIsoDep = (rfid.uid.sak & 0x20) != 0;

  if (esIsoDep) {
    uint8_t token[TOKEN_BYTES];
    ResultadoCelular r = intentarPagoCelular(token);
    rfid.PICC_HaltA();   // por si DESELECT no llegó; si llegó, el objetivo ya está en HALT
    tiempoUltimoCelular = millis();
    hayCelularReciente = true;

    if (r == CEL_TOKEN) {
      Serial.println("[NFC] Telefono con la app: token recibido");
      enviarLectura(true, aHex(token, TOKEN_BYTES));
      return;
    }
    if (r == CEL_SIN_PAGO) {
      Serial.println("[NFC] App detectada, pero el pago no esta activo");
      lcdMostrar("ABRA LA APP Y", "TOQUE SU TARJETA");
      delay(2000);
      mostrarEspera();
      return;
    }
    if (r == CEL_ERROR && uidAleatorio) {
      Serial.println("[NFC] Se perdio la comunicacion con el telefono");
      lcdMostrar("ACERQUE DE NUEVO", "Celular quieto");
      delay(2000);
      mostrarEspera();
      return;
    }
    // CEL_SIN_APP, o tarjeta ISO-DEP (ej. DESFire) sin la app: sigue como tarjeta por UID
  } else {
    rfid.PICC_HaltA();
    rfid.PCD_StopCrypto1();
  }

  if (uidAleatorio) {
    Serial.println("[NFC] Telefono sin la app de peaje (UID aleatorio " + uidHex + ")");
    lcdMostrar("TELEFONO SIN APP", "Use app Peaje");
    delay(2000);
    mostrarEspera();
    return;
  }

  // La misma tarjeta apoyada en el lector no se envía de nuevo durante unos segundos
  if (uidHex == ultimoUid && millis() - tiempoUltimoUid < PAUSA_MISMA_TARJETA_MS) return;
  ultimoUid = uidHex;
  tiempoUltimoUid = millis();

  Serial.println("[RFID] Tarjeta UID: " + uidHex);
  enviarLectura(false, uidHex);
}

// Activa ISO-DEP, envía SELECT AID y, si la app responde con un token, lo copia en `token`.
ResultadoCelular intentarPagoCelular(uint8_t* token) {
  ResultadoCelular resultado = CEL_ERROR;

  if (isoActivar()) {
    uint8_t apdu[5 + sizeof(AID_PEAJE) + 1];
    apdu[0] = 0x00; apdu[1] = 0xA4; apdu[2] = 0x04; apdu[3] = 0x00; apdu[4] = sizeof(AID_PEAJE);
    memcpy(apdu + 5, AID_PEAJE, sizeof(AID_PEAJE));
    apdu[sizeof(apdu) - 1] = 0x00;  // Le

    uint8_t resp[64];
    uint16_t respLen = sizeof(resp);
    if (isoEnviarApdu(apdu, sizeof(apdu), resp, &respLen) && respLen >= 2) {
      uint16_t sw = (resp[respLen - 2] << 8) | resp[respLen - 1];
      if (sw == SW_OK && respLen - 2 == TOKEN_BYTES) {
        memcpy(token, resp, TOKEN_BYTES);
        resultado = CEL_TOKEN;
      } else if (sw == SW_SIN_PAGO_ACTIVO) {
        resultado = CEL_SIN_PAGO;
      } else {
        Serial.printf("[NFC] Dispositivo ISO-DEP sin la app (SW=%04X, %u bytes)\n", sw, respLen);
        resultado = CEL_SIN_APP;
      }
    }
    isoDeseleccionar();
  }

  isoConfigurarTimer(25);   // vuelve al valor de PCD_Init() para la lectura normal de tarjetas
  return resultado;
}

// ============================================================
// ISO-DEP (ISO/IEC 14443-4) SOBRE EL RC522
// ============================================================
// Programa el timer del RC522 (25 us por cuenta, ver PCD_Init) para que expire tras `ms`.
void isoConfigurarTimer(uint16_t ms) {
  uint32_t cuentas = (uint32_t)ms * 40;
  if (cuentas > 0xFFFF) cuentas = 0xFFFF;
  rfid.PCD_WriteRegister(MFRC522::TReloadRegH, (uint8_t)(cuentas >> 8));
  rfid.PCD_WriteRegister(MFRC522::TReloadRegL, (uint8_t)(cuentas & 0xFF));
}

// Envía una trama (agrega CRC_A) y espera la respuesta (verifica y quita el CRC_A).
// No usa PCD_TransceiveData porque la librería corta la espera a los 36 ms, y un teléfono
// puede tardar más (la app se despierta) o pedir más tiempo con S(WTX).
bool isoTransceive(const uint8_t* tx, uint8_t txLen, uint8_t* rx, uint8_t* rxLen, uint16_t timeoutMs) {
  uint8_t trama[ISO_FIFO];
  if (txLen + 2 > ISO_FIFO) return false;
  memcpy(trama, tx, txLen);
  // El cálculo de CRC usa la FIFO: debe hacerse ANTES de cargar la trama a enviar
  if (rfid.PCD_CalculateCRC(trama, txLen, trama + txLen) != MFRC522::STATUS_OK) return false;

  if (timeoutMs > ISO_TIMEOUT_MAX_MS) timeoutMs = ISO_TIMEOUT_MAX_MS;
  isoConfigurarTimer(timeoutMs);

  rfid.PCD_WriteRegister(MFRC522::CommandReg, MFRC522::PCD_Idle);
  rfid.PCD_WriteRegister(MFRC522::ComIrqReg, 0x7F);          // limpia interrupciones
  rfid.PCD_WriteRegister(MFRC522::FIFOLevelReg, 0x80);       // vacía la FIFO
  rfid.PCD_WriteRegister(MFRC522::FIFODataReg, txLen + 2, trama);
  rfid.PCD_WriteRegister(MFRC522::BitFramingReg, 0x00);      // bytes completos
  rfid.PCD_WriteRegister(MFRC522::CommandReg, MFRC522::PCD_Transceive);
  rfid.PCD_SetRegisterBitMask(MFRC522::BitFramingReg, 0x80); // StartSend

  // El timer (TAuto) parte al terminar de transmitir; el plazo por software es sólo un respaldo
  unsigned long inicio = millis();
  while (true) {
    uint8_t irq = rfid.PCD_ReadRegister(MFRC522::ComIrqReg);
    if (irq & 0x30) break;                  // RxIRq | IdleIRq: llegó la respuesta
    if (irq & 0x01) return false;           // TimerIRq: no respondió a tiempo
    if (millis() - inicio > (unsigned long)timeoutMs + 50) return false;
    yield();
  }

  // BufferOvfl | CollErr | ParityErr | ProtocolErr
  if (rfid.PCD_ReadRegister(MFRC522::ErrorReg) & 0x1B) return false;
  uint8_t n = rfid.PCD_ReadRegister(MFRC522::FIFOLevelReg);
  if (n < 3 || n > ISO_FIFO) return false;          // al menos PCB + CRC
  if (rfid.PCD_ReadRegister(MFRC522::ControlReg) & 0x07) return false;  // trama con bits sueltos

  uint8_t buf[ISO_FIFO];
  rfid.PCD_ReadRegister(MFRC522::FIFODataReg, n, buf, 0);

  uint8_t crc[2];
  if (rfid.PCD_CalculateCRC(buf, n - 2, crc) != MFRC522::STATUS_OK) return false;
  if (crc[0] != buf[n - 2] || crc[1] != buf[n - 1]) return false;

  if (n - 2 > *rxLen) return false;
  memcpy(rx, buf, n - 2);
  *rxLen = n - 2;
  return true;
}

// Microsegundos de un tiempo de espera ISO 14443-4 (FWT/SFGT) a partir de su exponente 0..14
uint32_t isoTiempoUs(uint8_t exponente) {
  return (uint32_t)(256UL * 16UL * 1000000ULL / 13560000ULL) * (1UL << exponente);  // ~302 us x 2^n
}

// RATS -> ATS. Deja listos isoFsc, isoFwtMs y el número de bloque.
bool isoActivar() {
  isoBloque = 0;
  isoFsc = 16;
  isoFwtMs = 5;

  uint8_t rats[2] = {0xE0, (uint8_t)(ISO_FSDI << 4)};   // CID = 0
  uint8_t ats[ISO_FIFO];
  uint8_t atsLen = sizeof(ats);
  if (!isoTransceive(rats, sizeof(rats), ats, &atsLen, 30)) {
    Serial.println("[NFC] Sin respuesta a RATS");
    return false;
  }
  if (atsLen < 1 || ats[0] != atsLen) {
    Serial.println("[NFC] ATS invalido");
    return false;
  }

  uint8_t sfgi = 0;
  if (atsLen >= 2) {
    uint8_t t0 = ats[1];
    static const uint16_t FSC_TABLA[] = {16, 24, 32, 40, 48, 64, 96, 128, 256};
    uint8_t fsci = t0 & 0x0F;
    isoFsc = FSC_TABLA[fsci > 8 ? 8 : fsci];
    uint8_t i = 2;
    if (t0 & 0x10) i++;                         // TA(1): velocidades (se queda en 106 kbit/s)
    if ((t0 & 0x20) && i < atsLen) {            // TB(1): FWI y SFGI
      uint8_t fwi = ats[i] >> 4;
      sfgi = ats[i] & 0x0F;
      if (fwi > 14) fwi = 4;
      if (sfgi > 14) sfgi = 0;
      isoFwtMs = isoTiempoUs(fwi) / 1000 + 1;
    }
  }
  Serial.printf("[NFC] ISO-DEP activo: FSC=%u FWT=%u ms\n", isoFsc, isoFwtMs);

  // Tiempo de guarda antes del primer bloque (SFGT), acotado a 100 ms
  if (sfgi) {
    uint32_t sfgtMs = isoTiempoUs(sfgi) / 1000 + 1;
    delay(sfgtMs > 100 ? 100 : sfgtMs);
  }
  return true;
}

// Envía un APDU en un bloque I y devuelve la respuesta completa (datos + SW1 SW2).
// Responde los S(WTX) del teléfono y junta las respuestas encadenadas con R(ACK).
bool isoEnviarApdu(const uint8_t* apdu, uint8_t apduLen, uint8_t* resp, uint16_t* respLen) {
  uint8_t tx[ISO_FIFO];
  uint8_t txLen = 1 + apduLen;
  if (txLen + 2 > isoFsc || txLen + 2 > ISO_FIFO) {
    Serial.println("[NFC] APDU demasiado largo para el telefono");
    return false;
  }
  tx[0] = 0x02 | isoBloque;            // bloque I, sin CID/NAD, sin encadenar
  memcpy(tx + 1, apdu, apduLen);

  uint16_t total = 0;
  uint16_t timeout = isoFwtMs + ISO_MARGEN_MS;
  uint8_t pedidosWtx = 0;

  while (true) {
    uint8_t rx[ISO_FIFO];
    uint8_t rxLen = sizeof(rx);
    if (!isoTransceive(tx, txLen, rx, &rxLen, timeout) || rxLen == 0) {
      Serial.println("[NFC] Sin respuesta valida del telefono");
      return false;
    }
    uint8_t pcb = rx[0];
    uint8_t inicio = 1 + ((pcb & 0x08) ? 1 : 0);   // salta CID si viene

    // S(WTX): el teléfono pide más tiempo -> se le devuelve el mismo bloque
    if ((pcb & 0xF7) == 0xF2) {
      if (++pedidosWtx > ISO_MAX_WTX || rxLen <= inicio) return false;
      uint8_t wtxm = rx[inicio] & 0x3F;
      if (wtxm == 0) wtxm = 1;
      memcpy(tx, rx, inicio);
      tx[inicio] = wtxm;
      txLen = inicio + 1;
      uint32_t t = (uint32_t)isoFwtMs * wtxm + ISO_MARGEN_MS;
      timeout = t > ISO_TIMEOUT_MAX_MS ? ISO_TIMEOUT_MAX_MS : t;
      continue;
    }

    // Bloque I con datos de la respuesta
    if ((pcb & 0xE2) == 0x02) {
      if ((pcb & 0x01) != isoBloque) return false;   // número de bloque inesperado
      isoBloque ^= 1;
      if (pcb & 0x04) inicio++;                        // salta NAD si viene
      uint8_t datos = rxLen > inicio ? rxLen - inicio : 0;
      if (total + datos > *respLen) return false;
      memcpy(resp + total, rx + inicio, datos);
      total += datos;

      if (pcb & 0x10) {                    // encadenado: pedir el siguiente trozo con R(ACK)
        tx[0] = 0xA2 | isoBloque;
        txLen = 1;
        timeout = isoFwtMs + ISO_MARGEN_MS;
        continue;
      }
      *respLen = total;
      return true;
    }

    Serial.printf("[NFC] Bloque inesperado PCB=%02X\n", pcb);
    return false;
  }
}

// S(DESELECT): el teléfono queda en HALT y no vuelve a responder hasta que se aleje del lector
void isoDeseleccionar() {
  uint8_t deselect[1] = {0xC2};
  uint8_t rx[4];
  uint8_t rxLen = sizeof(rx);
  isoTransceive(deselect, 1, rx, &rxLen, isoFwtMs + ISO_MARGEN_MS);
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

// Callback MQTT. El servidor responde JSON:
// {"id":"...","orden":"ABRIR","estado":"APROBADO","saldo":4100.0,...}
void recibirOrdenMQTT(char* topic, byte* payload, unsigned int length) {
  String msg;
  msg.reserve(length);
  for (unsigned int i = 0; i < length; i++) msg += (char)payload[i];
  msg.trim();
  Serial.println("[MQTT] <- " + msg);

  String orden, estado, saldo;
  if (msg.startsWith("{")) {
    // Sólo se acepta la respuesta de la última lectura enviada
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
    Serial.print("[MQTT] Conectando al servidor...");
    String clientId = String("ESP32_") + GARITA_ID + "_" + CARRIL_ID;
    // El broker exige usuario y clave: el usuario es el código de la garita
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
  else if (estado.length() > 0)                   lcdMostrar("ACCESO DENEGADO", estado);
  else                                            lcdMostrar("ACCESO DENEGADO", "SALDO / INVALIDA");
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
