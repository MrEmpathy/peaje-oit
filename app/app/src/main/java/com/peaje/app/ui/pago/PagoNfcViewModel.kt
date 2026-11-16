package com.peaje.app.ui.pago

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaje.app.data.PeajeRepository
import com.peaje.app.data.model.Transaccion
import com.peaje.app.nfc.EstadoNfc
import com.peaje.app.nfc.ProtocoloApdu
import com.peaje.app.nfc.SesionPagoNfc
import com.peaje.app.ui.formatearPesos
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface EstadoPago {
    data object Preparando : EstadoPago
    data object NfcNoSoportado : EstadoPago
    data object NfcApagado : EstadoPago

    /** Token activo: el teléfono responde al lector. [leido] = el lector ya recibió el token. */
    data class Listo(val segundosRestantes: Int, val leido: Boolean = false) : EstadoPago

    data class Resultado(val aprobado: Boolean, val titulo: String, val detalle: String) : EstadoPago
    data class Error(val mensaje: String) : EstadoPago
}

/**
 * Flujo del pago NFC:
 *  1. Pide un token de un solo uso al backend y lo deja disponible para el servicio HCE.
 *  2. El lector (ESP32 + PN532) lo lee con un SELECT AID y lo publica por MQTT; el backend cobra.
 *  3. Se consulta el historial hasta que aparece la transacción nueva y se muestra el resultado.
 * El token se renueva solo antes de expirar y se desactiva al cerrar la pantalla.
 */
class PagoNfcViewModel(
    private val repositorio: PeajeRepository,
    private val estadoNfc: () -> EstadoNfc,
) : ViewModel() {

    var estado by mutableStateOf<EstadoPago>(EstadoPago.Preparando)
        private set

    private var trabajo: Job? = null

    /** @param forzar reinicia aunque ya haya un pago en curso (botón Reintentar). */
    fun iniciar(forzar: Boolean = false) {
        if (!forzar && trabajo?.isActive == true) return
        detener()

        when (estadoNfc()) {
            EstadoNfc.NO_SOPORTADO -> { estado = EstadoPago.NfcNoSoportado; return }
            EstadoNfc.APAGADO -> { estado = EstadoPago.NfcApagado; return }
            EstadoNfc.DISPONIBLE -> Unit
        }

        estado = EstadoPago.Preparando
        trabajo = viewModelScope.launch {
            // Última transacción conocida: el resultado será la primera transacción posterior.
            val idBase = repositorio.transaccionesRecientes(1)
                .getOrElse { fallar(it); return@launch }
                .firstOrNull()?.id ?: 0L

            if (!activarTokenNuevo()) return@launch
            val entregasIniciales = SesionPagoNfc.entregas.value

            launch {
                SesionPagoNfc.entregas.collect { n ->
                    val actual = estado
                    if (n > entregasIniciales && actual is EstadoPago.Listo && !actual.leido) {
                        estado = actual.copy(leido = true)
                    }
                }
            }

            var segundos = 0
            while (isActive) {
                if (SesionPagoNfc.segundosRestantes() <= RENOVAR_CON_SEGUNDOS && !activarTokenNuevo()) return@launch
                (estado as? EstadoPago.Listo)?.let { estado = it.copy(segundosRestantes = SesionPagoNfc.segundosRestantes()) }

                if (segundos % INTERVALO_CONSULTA_S == 0) {
                    // Errores de red pasajeros se ignoran: se vuelve a consultar en el siguiente ciclo.
                    val nueva = repositorio.transaccionesRecientes(5).getOrNull()
                        ?.firstOrNull { it.id > idBase && it.metodo == "TOKEN" }
                    if (nueva != null) {
                        mostrarResultado(nueva)
                        return@launch
                    }
                }
                delay(1_000)
                segundos++
            }
        }
    }

    /** Cierra el pago: el teléfono deja de responder al lector. */
    fun cancelar() {
        detener()
        estado = EstadoPago.Preparando
    }

    private fun detener() {
        trabajo?.cancel()
        trabajo = null
        SesionPagoNfc.desactivar()
    }

    private suspend fun activarTokenNuevo(): Boolean {
        val respuesta = repositorio.emitirTokenNfc().getOrElse { fallar(it); return false }
        val bytes = runCatching { ProtocoloApdu.hexABytes(respuesta.token) }.getOrNull()
        if (bytes == null) {
            fallar(Exception("El servidor entregó un código de pago inválido"))
            return false
        }
        SesionPagoNfc.activar(bytes, respuesta.validoPorSegundos)
        val leido = (estado as? EstadoPago.Listo)?.leido ?: false
        estado = EstadoPago.Listo(SesionPagoNfc.segundosRestantes(), leido)
        return true
    }

    private suspend fun mostrarResultado(t: Transaccion) {
        SesionPagoNfc.desactivar()
        estado = describir(t)
        repositorio.refrescarUsuario()
    }

    private fun fallar(e: Throwable) {
        SesionPagoNfc.desactivar()
        estado = EstadoPago.Error(e.message ?: "No se pudo preparar el pago")
    }

    override fun onCleared() {
        SesionPagoNfc.desactivar()
    }

    companion object {
        private const val RENOVAR_CON_SEGUNDOS = 20
        private const val INTERVALO_CONSULTA_S = 2

        fun describir(t: Transaccion): EstadoPago.Resultado {
            val tarifa = formatearPesos(t.tarifaCobrada)
            val saldo = t.saldoRestante?.let(::formatearPesos) ?: "-"
            return when (t.estado) {
                "APROBADO" -> EstadoPago.Resultado(true, "Pago aprobado", "Se cobraron $tarifa. Saldo: $saldo")
                "LECTURA_DUPLICADA" -> EstadoPago.Resultado(true, "Paso ya registrado", "No se volvió a cobrar. Saldo: $saldo")
                "SALDO_INSUFICIENTE" -> EstadoPago.Resultado(
                    false, "Saldo insuficiente",
                    "La tarifa es $tarifa y tu saldo es ${t.saldo?.let(::formatearPesos) ?: "-"}. Recarga e inténtalo de nuevo.",
                )
                "TARIFA_NO_DEFINIDA" -> EstadoPago.Resultado(false, "Pago rechazado", "Esta garita no tiene tarifa para tu tipo de vehículo.")
                "TOKEN_EXPIRADO", "TOKEN_INVALIDO", "TOKEN_REUTILIZADO" ->
                    EstadoPago.Resultado(false, "No se pudo validar el pago", "Vuelve a tocar tu tarjeta e inténtalo de nuevo.")
                "TRANSACCION_INVALIDA" -> EstadoPago.Resultado(false, "Pago rechazado", "La garita o tu cuenta no están habilitadas.")
                else -> EstadoPago.Resultado(false, "Pago rechazado", "Estado: ${t.estado}")
            }
        }
    }
}
