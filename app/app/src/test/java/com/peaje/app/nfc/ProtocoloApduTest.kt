package com.peaje.app.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocoloApduTest {

    private val token = ProtocoloApdu.hexABytes("01000000016AFAB589CE" + "AB".repeat(23)) // 33 bytes

    /** SELECT AID tal como lo envía el firmware del ESP32 (con Le = 00 al final). */
    private val selectPeaje = ProtocoloApdu.hexABytes("00A4040007F05045414A450100")

    @Test
    fun `el AID es F0 + PEAJE + 01`() {
        assertEquals("F05045414A4501", ProtocoloApdu.AID.joinToString("") { "%02X".format(it) })
        assertEquals(33, token.size)
    }

    @Test
    fun `select con pago activo entrega el token seguido de 9000`() {
        val r = ProtocoloApdu.procesar(selectPeaje, token)
        assertTrue(r.tokenEntregado)
        assertArrayEquals(token + byteArrayOf(0x90.toByte(), 0x00), r.bytes)
        assertEquals(35, r.bytes.size)
    }

    @Test
    fun `select sin Le tambien funciona`() {
        val sinLe = selectPeaje.copyOf(selectPeaje.size - 1)
        assertTrue(ProtocoloApdu.procesar(sinLe, token).tokenEntregado)
    }

    @Test
    fun `sin pago activo responde 6985 y no entrega nada`() {
        val r = ProtocoloApdu.procesar(selectPeaje, tokenVigente = null)
        assertFalse(r.tokenEntregado)
        assertArrayEquals(ProtocoloApdu.SW_SIN_PAGO_ACTIVO, r.bytes)
    }

    @Test
    fun `otro AID responde 6A82`() {
        val otroAid = ProtocoloApdu.hexABytes("00A4040007A000000003101000")
        val r = ProtocoloApdu.procesar(otroAid, token)
        assertFalse(r.tokenEntregado)
        assertArrayEquals(ProtocoloApdu.SW_AID_NO_ENCONTRADO, r.bytes)
    }

    @Test
    fun `comandos mal formados o no soportados no entregan el token`() {
        val casos = mapOf(
            "00A4" to ProtocoloApdu.SW_LONGITUD_INCORRECTA,                 // muy corto
            "00A40400" to ProtocoloApdu.SW_LONGITUD_INCORRECTA,             // sin Lc
            "00A4040007F050" to ProtocoloApdu.SW_LONGITUD_INCORRECTA,       // Lc mayor que los datos
            "00B0000000" to ProtocoloApdu.SW_INS_NO_SOPORTADA,              // READ BINARY
            "80A4040007F05045414A4501" to ProtocoloApdu.SW_CLA_NO_SOPORTADA,
            "00A4000C023F00" to ProtocoloApdu.SW_P1P2_INCORRECTOS,          // SELECT por FID
        )
        for ((hex, swEsperado) in casos) {
            val r = ProtocoloApdu.procesar(ProtocoloApdu.hexABytes(hex), token)
            assertFalse("No debía entregar token con $hex", r.tokenEntregado)
            assertArrayEquals("SW incorrecto para $hex", swEsperado, r.bytes)
        }
    }

    @Test
    fun `hex a bytes`() {
        assertArrayEquals(byteArrayOf(0x0A, 0xFF.toByte(), 0x00), ProtocoloApdu.hexABytes("0aff00"))
        assertTrue(runCatching { ProtocoloApdu.hexABytes("ABC") }.isFailure)  // largo impar
        assertTrue(runCatching { ProtocoloApdu.hexABytes("ZZ") }.isFailure)   // no hex
    }
}
