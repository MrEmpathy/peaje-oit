package com.peaje.app.nfc

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Puente entre la pantalla de pago y el servicio HCE (corren en el mismo proceso).
 * El token sólo se entrega mientras el usuario tiene abierta la pantalla de pago:
 * con la app cerrada o fuera de modo pago, el lector recibe 69 85.
 */
object SesionPagoNfc {

    /** Se descuenta a la vigencia que informa el servidor, para no entregar tokens a punto de expirar. */
    private const val MARGEN_MS = 5_000L

    private val lock = Any()
    private var token: ByteArray? = null
    private var expiraEnMs = 0L

    private val _entregas = MutableStateFlow(0)
    /** Cuántas veces se entregó un token a un lector (sirve para avisar a la pantalla). */
    val entregas: StateFlow<Int> = _entregas.asStateFlow()

    fun activar(token: ByteArray, validoPorSegundos: Int) = synchronized(lock) {
        this.token = token.copyOf()
        expiraEnMs = SystemClock.elapsedRealtime() + validoPorSegundos * 1000L - MARGEN_MS
    }

    fun desactivar() = synchronized(lock) {
        token = null
        expiraEnMs = 0L
    }

    fun tokenVigente(): ByteArray? = synchronized(lock) {
        token?.takeIf { SystemClock.elapsedRealtime() < expiraEnMs }?.copyOf()
    }

    fun segundosRestantes(): Int = synchronized(lock) {
        if (token == null) 0 else ((expiraEnMs - SystemClock.elapsedRealtime()) / 1000L).toInt().coerceAtLeast(0)
    }

    fun registrarEntrega() = _entregas.update { it + 1 }
}
