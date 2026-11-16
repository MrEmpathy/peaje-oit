package com.peaje.app.ui.pago

import com.peaje.app.data.model.Transaccion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DescribirResultadoTest {

    private fun tx(estado: String, saldo: Double? = 5000.0, tarifa: Double = 900.0, restante: Double? = 4100.0) =
        Transaccion(1, "TOKEN", "garita_norte", "carril1", "PRUE12", "AUTO", saldo, tarifa, restante, estado, null)

    @Test
    fun `aprobado muestra tarifa y saldo`() {
        val r = PagoNfcViewModel.describir(tx("APROBADO"))
        assertTrue(r.aprobado)
        assertEquals("Pago aprobado", r.titulo)
        assertTrue(r.detalle, r.detalle.contains("900") && r.detalle.contains("4.100"))
    }

    @Test
    fun `lectura duplicada cuenta como aprobado sin cobro`() {
        assertTrue(PagoNfcViewModel.describir(tx("LECTURA_DUPLICADA", tarifa = 0.0)).aprobado)
    }

    @Test
    fun `rechazos`() {
        for (estado in listOf("SALDO_INSUFICIENTE", "TARIFA_NO_DEFINIDA", "TOKEN_EXPIRADO", "TOKEN_REUTILIZADO", "TRANSACCION_INVALIDA", "OTRO")) {
            assertFalse(estado, PagoNfcViewModel.describir(tx(estado)).aprobado)
        }
        assertEquals("Saldo insuficiente", PagoNfcViewModel.describir(tx("SALDO_INSUFICIENTE", saldo = 200.0)).titulo)
    }
}
