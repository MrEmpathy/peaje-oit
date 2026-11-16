package com.peaje.app.data

import com.peaje.app.data.LimiteIntentosLogin.Companion.BLOQUEO_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimiteIntentosLoginTest {

    private class AlmacenMemoria : LimiteIntentosLogin.Almacen {
        override var fallidos = 0
        override var bloqueadoHasta = 0L
    }

    private var ahora = 1_000_000L
    private val almacen = AlmacenMemoria()
    private val limite = LimiteIntentosLogin(almacen) { ahora }

    @Test
    fun `tres fallos bloquean y avisan los intentos que quedan`() {
        assertEquals(2, limite.registrarFallo())
        assertEquals(1, limite.registrarFallo())
        assertFalse(limite.estaBloqueado())
        assertEquals(0, limite.registrarFallo())
        assertTrue(limite.estaBloqueado())
        assertEquals(BLOQUEO_MS, limite.msRestantesBloqueo())
    }

    @Test
    fun `el bloqueo termina y entrega otros tres intentos`() {
        repeat(3) { limite.registrarFallo() }
        ahora += BLOQUEO_MS - 1
        assertTrue(limite.estaBloqueado())
        ahora += 1
        assertFalse(limite.estaBloqueado())
        assertEquals(2, limite.registrarFallo())
    }

    @Test
    fun `login correcto reinicia el contador`() {
        limite.registrarFallo()
        limite.registrarFallo()
        limite.reiniciar()
        assertEquals(2, limite.registrarFallo())
    }

    @Test
    fun `el estado sobrevive a una nueva instancia (reabrir la app)`() {
        repeat(3) { limite.registrarFallo() }
        val otra = LimiteIntentosLogin(almacen) { ahora + 10_000 }
        assertTrue(otra.estaBloqueado())
        assertEquals(BLOQUEO_MS - 10_000, otra.msRestantesBloqueo())
    }

    @Test
    fun `atrasar la hora del telefono no alarga el bloqueo`() {
        repeat(3) { limite.registrarFallo() }
        ahora -= 3_600_000
        assertEquals(BLOQUEO_MS, limite.msRestantesBloqueo())
    }
}
