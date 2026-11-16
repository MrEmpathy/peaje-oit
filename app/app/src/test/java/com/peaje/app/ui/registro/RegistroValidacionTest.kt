package com.peaje.app.ui.registro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistroValidacionTest {

    private val valido = RegistroUiState(
        nombre = "Ana", apellido = "Pérez", correo = "ana@correo.cl",
        password = "clave1234", confirmarPassword = "clave1234",
        patente = "bcdf12", tipoVehiculo = "AUTO",
    )

    @Test
    fun `formulario completo no tiene errores`() {
        assertTrue(RegistroViewModel.validar(valido).isEmpty())
    }

    @Test
    fun `formulario vacio marca todos los campos`() {
        assertEquals(Campo.entries.toSet(), RegistroViewModel.validar(RegistroUiState()).keys)
    }

    @Test
    fun `contrasena corta y que no coincide`() {
        val e = RegistroViewModel.validar(valido.copy(password = "corta", confirmarPassword = "otra"))
        assertEquals(setOf(Campo.PASSWORD, Campo.CONFIRMAR), e.keys)
    }

    @Test
    fun `correo invalido`() {
        for (correo in listOf("ana", "ana@", "ana@correo", "@correo.cl", "ana correo@x.cl")) {
            assertTrue(correo, Campo.CORREO in RegistroViewModel.validar(valido.copy(correo = correo)))
        }
    }

    @Test
    fun `patente se normaliza igual que en el servidor`() {
        assertEquals("ABCD12", RegistroViewModel.normalizarPatente("ab-cd·12"))
        assertEquals("AB1234", RegistroViewModel.normalizarPatente("AB 12 34"))
        assertEquals("ABC12", RegistroViewModel.normalizarPatente("abc.12"))
        assertNull(RegistroViewModel.normalizarPatente("ABCDEF"))      // sin números
        assertNull(RegistroViewModel.normalizarPatente("123456"))      // sin letras
        assertNull(RegistroViewModel.normalizarPatente("AB12"))        // muy corta
        assertNull(RegistroViewModel.normalizarPatente("ABCDE12345"))  // muy larga
    }
}
