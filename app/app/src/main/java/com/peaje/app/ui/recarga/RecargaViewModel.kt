package com.peaje.app.ui.recarga

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaje.app.data.PeajeRepository
import com.peaje.app.data.model.RecargaResponse
import com.peaje.app.ui.formatearPesos
import kotlinx.coroutines.launch

data class RecargaUiState(
    /** Sólo dígitos, en pesos enteros. */
    val montoTexto: String = "",
    val cargando: Boolean = false,
    val error: String? = null,
    val ultimaRecarga: RecargaResponse? = null,
) {
    val monto: Long get() = montoTexto.toLongOrNull() ?: 0L
}

class RecargaViewModel(private val repositorio: PeajeRepository) : ViewModel() {

    var uiState by mutableStateOf(RecargaUiState())
        private set

    fun onMontoChange(texto: String) {
        val digitos = texto.filter(Char::isDigit).trimStart('0').take(6)
        uiState = uiState.copy(montoTexto = digitos, error = null, ultimaRecarga = null)
    }

    fun seleccionarMonto(monto: Long) = onMontoChange(monto.toString())

    fun recargar() {
        if (uiState.cargando) return
        val monto = uiState.monto
        val error = when {
            monto <= 0 -> "Ingresa el monto a recargar"
            monto > MAXIMO -> "El máximo por recarga es ${formatearPesos(MAXIMO.toDouble())}"
            else -> null
        }
        if (error != null) {
            uiState = uiState.copy(error = error)
            return
        }

        uiState = uiState.copy(cargando = true, error = null, ultimaRecarga = null)
        viewModelScope.launch {
            repositorio.recargar(monto)
                .onSuccess { r -> uiState = RecargaUiState(ultimaRecarga = r) }
                .onFailure { e -> uiState = uiState.copy(cargando = false, error = e.message) }
        }
    }

    companion object {
        /** Igual a RECARGA_MAXIMA del backend (.env); el servidor también lo valida. */
        const val MAXIMO = 100_000L
        val MONTOS_RAPIDOS = listOf(2_000L, 5_000L, 10_000L, 20_000L)
    }
}
