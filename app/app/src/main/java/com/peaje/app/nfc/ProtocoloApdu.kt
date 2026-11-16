package com.peaje.app.nfc

/**
 * Protocolo entre el lector del peaje (ESP32 + PN532) y la app (HCE).
 *
 *   Lector  -> app : SELECT AID   00 A4 04 00 07 F0 50 45 41 4A 45 01 [00]
 *   App     -> lector: <token de 33 bytes> 90 00   si hay un pago activo
 *                      69 85                       si la app no está en modo pago
 *
 * El token es el que entrega POST /usuario/token-nfc (66 caracteres hex = 33 bytes).
 * El lector lo convierte a hex y lo publica en peaje/<garita>/<carril>/token.
 *
 * No depende de Android, para poder probarlo con tests unitarios.
 */
object ProtocoloApdu {

    /** AID propietario: F0 + "PEAJE" en ASCII + versión 01. Debe coincidir con res/xml/apduservice.xml y el firmware. */
    val AID: ByteArray = hexABytes("F05045414A4501")

    val SW_OK = sw(0x9000)
    val SW_SIN_PAGO_ACTIVO = sw(0x6985)      // condiciones de uso no satisfechas
    val SW_AID_NO_ENCONTRADO = sw(0x6A82)
    val SW_P1P2_INCORRECTOS = sw(0x6A86)
    val SW_LONGITUD_INCORRECTA = sw(0x6700)
    val SW_INS_NO_SOPORTADA = sw(0x6D00)
    val SW_CLA_NO_SOPORTADA = sw(0x6E00)

    private const val CLA_ISO: Byte = 0x00
    private const val INS_SELECT: Byte = 0xA4.toByte()
    private const val P1_POR_NOMBRE: Byte = 0x04

    class Respuesta(val bytes: ByteArray, val tokenEntregado: Boolean)

    /** @param tokenVigente token a entregar, o null si el usuario no activó el pago. */
    fun procesar(apdu: ByteArray, tokenVigente: ByteArray?): Respuesta {
        if (apdu.size < 4) return error(SW_LONGITUD_INCORRECTA)
        if (apdu[0] != CLA_ISO) return error(SW_CLA_NO_SOPORTADA)
        if (apdu[1] != INS_SELECT) return error(SW_INS_NO_SOPORTADA)
        if (apdu[2] != P1_POR_NOMBRE) return error(SW_P1P2_INCORRECTOS)
        if (apdu.size < 5) return error(SW_LONGITUD_INCORRECTA)

        val lc = apdu[4].toInt() and 0xFF
        if (lc == 0 || apdu.size < 5 + lc) return error(SW_LONGITUD_INCORRECTA)
        val aid = apdu.copyOfRange(5, 5 + lc)
        if (!aid.contentEquals(AID)) return error(SW_AID_NO_ENCONTRADO)

        if (tokenVigente == null) return error(SW_SIN_PAGO_ACTIVO)
        return Respuesta(tokenVigente + SW_OK, tokenEntregado = true)
    }

    fun hexABytes(hex: String): ByteArray {
        val limpio = hex.filterNot { it.isWhitespace() }
        require(limpio.length % 2 == 0) { "Hex con largo impar" }
        return ByteArray(limpio.length / 2) { i ->
            val alto = Character.digit(limpio[2 * i], 16)
            val bajo = Character.digit(limpio[2 * i + 1], 16)
            require(alto >= 0 && bajo >= 0) { "Carácter no hexadecimal" }
            ((alto shl 4) or bajo).toByte()
        }
    }

    private fun sw(valor: Int) = byteArrayOf((valor shr 8).toByte(), valor.toByte())
    private fun error(sw: ByteArray) = Respuesta(sw, tokenEntregado = false)
}
