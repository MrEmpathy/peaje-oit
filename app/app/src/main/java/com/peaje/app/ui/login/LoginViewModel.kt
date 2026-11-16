package com.peaje.app.ui.login

import android.util.Patterns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaje.app.data.CredencialesIncorrectasException
import com.peaje.app.data.LimiteIntentosLogin
import com.peaje.app.data.PeajeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class LoginUiState(
    val correo: String = "",
    val password: String = "",
    val correoError: String? = null,
    val passwordError: String? = null,
    val cargando: Boolean = false,
    val error: String? = null,
    /** Segundos que faltan para poder volver a intentar; 0 = no está bloqueado. */
    val segundosBloqueo: Int = 0,
) {
    val bloqueado: Boolean get() = segundosBloqueo > 0
}

/** Al iniciar sesión, el repositorio publica el usuario y MainActivity cambia de pantalla sola. */
class LoginViewModel(
    private val repositorio: PeajeRepository,
    private val limite: LimiteIntentosLogin,
) : ViewModel() {

    var uiState by mutableStateOf(LoginUiState())
        private set

    private var cuentaRegresiva: Job? = null

    init {
        // Si la app se cerró durante un bloqueo, sigue bloqueada al volver a abrirla.
        if (limite.estaBloqueado()) iniciarCuentaRegresiva()
    }

    fun onCorreoChange(valor: String) {
        uiState = uiState.copy(correo = valor, correoError = null, error = null)
    }

    fun onPasswordChange(valor: String) {
        uiState = uiState.copy(password = valor, passwordError = null, error = null)
    }

    fun iniciarSesion() {
        if (uiState.cargando || uiState.bloqueado) return
        if (limite.estaBloqueado()) {
            iniciarCuentaRegresiva()
            return
        }
        if (!validar()) return

        uiState = uiState.copy(cargando = true, error = null)
        viewModelScope.launch {
            repositorio.login(uiState.correo, uiState.password)
                .onSuccess {
                    limite.reiniciar()
                    uiState = uiState.copy(cargando = false, password = "")
                }
                .onFailure { e ->
                    uiState = uiState.copy(cargando = false, error = e.message)
                    // Sólo cuenta como intento fallido una contraseña o correo incorrectos,
                    // no un problema de red o del servidor.
                    if (e is CredencialesIncorrectasException) registrarFallo(e.message)
                }
        }
    }

    private fun registrarFallo(mensaje: String?) {
        val restantes = limite.registrarFallo()
        if (restantes == 0) {
            uiState = uiState.copy(password = "", error = null)
            iniciarCuentaRegresiva()
        } else {
            val intentos = if (restantes == 1) "Te queda 1 intento" else "Te quedan $restantes intentos"
            uiState = uiState.copy(password = "", error = "$mensaje. $intentos.")
        }
    }

    private fun iniciarCuentaRegresiva() {
        cuentaRegresiva?.cancel()
        cuentaRegresiva = viewModelScope.launch {
            while (true) {
                val ms = limite.msRestantesBloqueo()
                if (ms <= 0) break
                uiState = uiState.copy(segundosBloqueo = ((ms + 999) / 1000).toInt())
                delay(minOf(ms, 1000L))
            }
            uiState = uiState.copy(segundosBloqueo = 0)
        }
    }

    private fun validar(): Boolean {
        val correo = uiState.correo.trim()
        val correoError = when {
            correo.isEmpty() -> "Ingresa tu correo"
            !Patterns.EMAIL_ADDRESS.matcher(correo).matches() -> "Correo no válido"
            else -> null
        }
        val passwordError = if (uiState.password.isEmpty()) "Ingresa tu contraseña" else null
        uiState = uiState.copy(correoError = correoError, passwordError = passwordError)
        return correoError == null && passwordError == null
    }
}
