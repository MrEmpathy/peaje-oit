package com.peaje.app.ui.principal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaje.app.data.PeajeRepository
import com.peaje.app.data.SesionExpiradaException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Estado compartido por las pestañas: refresco del saldo y cierre de sesión. */
class PrincipalViewModel(private val repositorio: PeajeRepository) : ViewModel() {

    var refrescando by mutableStateOf(false)
        private set

    private val _mensajes = Channel<String>(Channel.BUFFERED)
    /** Mensajes de una sola vez para mostrar en un Snackbar. */
    val mensajes: Flow<String> = _mensajes.receiveAsFlow()

    fun refrescar() {
        if (refrescando) return
        refrescando = true
        viewModelScope.launch {
            repositorio.refrescarUsuario().onFailure { e ->
                // Si la sesión expiró, el repositorio ya la cerró y la app vuelve al login.
                if (e !is SesionExpiradaException) _mensajes.send(e.message ?: "No se pudo actualizar")
            }
            refrescando = false
        }
    }

    fun cerrarSesion() = repositorio.cerrarSesion()
}
