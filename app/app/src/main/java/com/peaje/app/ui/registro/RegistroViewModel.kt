package com.peaje.app.ui.registro

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peaje.app.data.PeajeRepository
import com.peaje.app.data.model.RegistroRequest
import com.peaje.app.data.model.TipoVehiculo
import kotlinx.coroutines.launch

data class RegistroUiState(
    val nombre: String = "",
    val apellido: String = "",
    val correo: String = "",
    val password: String = "",
    val confirmarPassword: String = "",
    val patente: String = "",
    val tipoVehiculo: String? = null,
    val tipos: List<TipoVehiculo> = emptyList(),
    val cargandoTipos: Boolean = false,
    val errorTipos: String? = null,
    val errores: Map<Campo, String> = emptyMap(),
    val cargando: Boolean = false,
    val error: String? = null,
)

enum class Campo { NOMBRE, APELLIDO, CORREO, PASSWORD, CONFIRMAR, PATENTE, TIPO }

/**
 * Crea la cuenta con POST /registro. El servidor responde con el token, así que al terminar
 * la sesión queda iniciada y MainActivity muestra la pantalla principal sola.
 */
class RegistroViewModel(private val repositorio: PeajeRepository) : ViewModel() {

    var uiState by mutableStateOf(RegistroUiState())
        private set

    init {
        cargarTipos()
    }

    fun cargarTipos() {
        if (uiState.cargandoTipos) return
        uiState = uiState.copy(cargandoTipos = true, errorTipos = null)
        viewModelScope.launch {
            repositorio.tiposVehiculo()
                .onSuccess { tipos ->
                    val seleccion = uiState.tipoVehiculo?.takeIf { actual -> tipos.any { it.codigo == actual } }
                    uiState = uiState.copy(tipos = tipos, tipoVehiculo = seleccion, cargandoTipos = false)
                }
                .onFailure { e -> uiState = uiState.copy(cargandoTipos = false, errorTipos = e.message) }
        }
    }

    fun onCambio(campo: Campo, valor: String) {
        uiState = when (campo) {
            Campo.NOMBRE -> uiState.copy(nombre = valor)
            Campo.APELLIDO -> uiState.copy(apellido = valor)
            Campo.CORREO -> uiState.copy(correo = valor)
            Campo.PASSWORD -> uiState.copy(password = valor)
            Campo.CONFIRMAR -> uiState.copy(confirmarPassword = valor)
            Campo.PATENTE -> uiState.copy(patente = valor.uppercase())
            Campo.TIPO -> uiState.copy(tipoVehiculo = valor)
        }.copy(errores = uiState.errores - campo, error = null)
    }

    fun registrar() {
        if (uiState.cargando) return
        val errores = validar(uiState)
        uiState = uiState.copy(errores = errores, error = null)
        if (errores.isNotEmpty()) return

        val datos = RegistroRequest(
            nombre = uiState.nombre.trim(),
            apellido = uiState.apellido.trim(),
            correo = uiState.correo.trim(),
            password = uiState.password,
            patente = normalizarPatente(uiState.patente)!!,
            tipoVehiculo = uiState.tipoVehiculo!!,
        )
        uiState = uiState.copy(cargando = true)
        viewModelScope.launch {
            repositorio.registrar(datos)
                // La cuenta quedó creada y la sesión iniciada: se limpia el formulario (contraseñas incluidas).
                .onSuccess { uiState = RegistroUiState(tipos = uiState.tipos) }
                .onFailure { e -> uiState = uiState.copy(cargando = false, error = e.message) }
        }
    }

    companion object {
        const val MIN_PASSWORD = 8
        const val MAX_PASSWORD = 72  // bcrypt sólo usa los primeros 72 bytes
        const val MAX_NOMBRE = 80
        private val SEPARADORES_PATENTE = Regex("[\\s.\\-·_]")
        // Regex propia (no android.util.Patterns) para poder probar validar() con JUnit sin Android.
        private val FORMATO_CORREO = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")
        private val FORMATO_PATENTE = Regex("^(?=.*[A-Z])(?=.*[0-9])[A-Z0-9]{5,8}$")

        /** Igual que normalizar_patente() del servidor: "ab-cd·12" -> "ABCD12"; null si no es válida. */
        fun normalizarPatente(texto: String): String? =
            texto.replace(SEPARADORES_PATENTE, "").uppercase().takeIf { FORMATO_PATENTE.matches(it) }

        /** Mismas reglas que RegistroRequest del servidor, para avisar antes de enviar. */
        fun validar(s: RegistroUiState): Map<Campo, String> = buildMap {
            fun nombre(campo: Campo, valor: String, etiqueta: String) = when {
                valor.isBlank() -> put(campo, "Ingresa tu $etiqueta")
                valor.trim().length > MAX_NOMBRE -> put(campo, "Máximo $MAX_NOMBRE caracteres")
                else -> Unit
            }
            nombre(Campo.NOMBRE, s.nombre, "nombre")
            nombre(Campo.APELLIDO, s.apellido, "apellido")

            val correo = s.correo.trim()
            when {
                correo.isEmpty() -> put(Campo.CORREO, "Ingresa tu correo")
                !FORMATO_CORREO.matches(correo) -> put(Campo.CORREO, "Correo no válido")
            }

            val bytes = s.password.toByteArray(Charsets.UTF_8).size
            when {
                s.password.isEmpty() -> put(Campo.PASSWORD, "Ingresa una contraseña")
                s.password.length < MIN_PASSWORD -> put(Campo.PASSWORD, "Mínimo $MIN_PASSWORD caracteres")
                bytes > MAX_PASSWORD -> put(Campo.PASSWORD, "Máximo $MAX_PASSWORD caracteres")
            }
            when {
                s.confirmarPassword.isEmpty() -> put(Campo.CONFIRMAR, "Repite la contraseña")
                s.confirmarPassword != s.password -> put(Campo.CONFIRMAR, "Las contraseñas no coinciden")
            }

            when {
                s.patente.isBlank() -> put(Campo.PATENTE, "Ingresa la patente")
                normalizarPatente(s.patente) == null ->
                    put(Campo.PATENTE, "5 a 8 letras y números, con al menos uno de cada (ej. BCDF12)")
            }
            if (s.tipoVehiculo == null) put(Campo.TIPO, "Elige el tipo de vehículo")
        }
    }
}
