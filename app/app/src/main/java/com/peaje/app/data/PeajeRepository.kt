package com.peaje.app.data

import com.google.gson.JsonParser
import com.peaje.app.data.model.LoginRequest
import com.peaje.app.data.model.RecargaRequest
import com.peaje.app.data.model.RecargaResponse
import com.peaje.app.data.model.RegistroRequest
import com.peaje.app.data.model.TipoVehiculo
import com.peaje.app.data.model.TokenNfcResponse
import com.peaje.app.data.model.Transaccion
import com.peaje.app.data.model.Usuario
import com.peaje.app.data.remote.PeajeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import java.io.IOException

class SesionExpiradaException : Exception("Tu sesión expiró. Inicia sesión nuevamente.")

/** El servidor rechazó el correo o la contraseña (401 en /login). Cuenta como intento fallido. */
class CredencialesIncorrectasException(mensaje: String) : Exception(mensaje)

/**
 * Única fuente de verdad de la sesión. [usuario] es null cuando no hay sesión;
 * las pantallas lo observan y se actualizan solas tras login, recargas o logout.
 */
class PeajeRepository(
    private val api: PeajeApi,
    private val session: SessionManager,
) {
    private val _usuario = MutableStateFlow(session.usuarioGuardado())
    val usuario: StateFlow<Usuario?> = _usuario.asStateFlow()

    suspend fun login(correo: String, password: String): Result<Usuario> = llamar(sinSesion = true) {
        val respuesta = api.login(LoginRequest(correo.trim().lowercase(), password))
        session.guardar(respuesta.accessToken, respuesta.usuario)
        _usuario.value = respuesta.usuario
        respuesta.usuario
    }

    /** Crea la cuenta y deja la sesión iniciada (el servidor responde con el token, igual que /login). */
    suspend fun registrar(datos: RegistroRequest): Result<Usuario> = llamar(sinSesion = true, esRegistro = true) {
        val respuesta = api.registro(datos.copy(correo = datos.correo.trim().lowercase()))
        session.guardar(respuesta.accessToken, respuesta.usuario)
        _usuario.value = respuesta.usuario
        respuesta.usuario
    }

    /** Tipos de vehículo para el selector del registro. */
    suspend fun tiposVehiculo(): Result<List<TipoVehiculo>> = llamar(sinSesion = true) {
        api.tiposVehiculo().tiposVehiculo
    }

    /** Vuelve a pedir saldo y datos del usuario al servidor. */
    suspend fun refrescarUsuario(): Result<Usuario> = llamar {
        val usuario = api.saldo().aUsuario()
        guardarUsuario(usuario)
        usuario
    }

    suspend fun recargar(monto: Long): Result<RecargaResponse> = llamar {
        val respuesta = api.recargar(RecargaRequest(monto))
        _usuario.value?.let { guardarUsuario(it.copy(saldo = respuesta.saldoActual)) }
        respuesta
    }

    /** Token de un solo uso para pagar por NFC (HCE). */
    suspend fun emitirTokenNfc(): Result<TokenNfcResponse> = llamar { api.tokenNfc() }

    /** Últimos pasos por el peaje, del más reciente al más antiguo. */
    suspend fun transaccionesRecientes(limite: Int = 5): Result<List<Transaccion>> = llamar {
        api.historial(limite).historial
    }

    fun cerrarSesion() {
        session.cerrarSesion()
        _usuario.value = null
    }

    private fun guardarUsuario(usuario: Usuario) {
        if (session.token == null) return  // se cerró la sesión mientras llegaba la respuesta
        session.actualizarUsuario(usuario)
        _usuario.value = usuario
    }

    /** Ejecuta una llamada a la API y traduce los errores a mensajes listos para mostrar. */
    private suspend fun <T> llamar(
        sinSesion: Boolean = false,
        esRegistro: Boolean = false,
        bloque: suspend () -> T,
    ): Result<T> {
        return try {
            Result.success(bloque())
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            when {
                sinSesion && e.code() == 401 ->
                    Result.failure(CredencialesIncorrectasException(leerDetalle(e) ?: "Correo o contraseña incorrectos"))
                e.code() == 401 -> {
                    cerrarSesion()
                    Result.failure(SesionExpiradaException())
                }
                else -> Result.failure(Exception(mensajeDeError(e, sinSesion, esRegistro)))
            }
        } catch (e: IOException) {
            Result.failure(Exception("No se pudo conectar con el servidor. Revisa que estés en la misma red Wi-Fi que el peaje."))
        } catch (e: Exception) {
            Result.failure(Exception("Respuesta inesperada del servidor."))
        }
    }

    private fun mensajeDeError(e: HttpException, sinSesion: Boolean, esRegistro: Boolean): String {
        val detalle = leerDetalle(e)
        return when (e.code()) {
            401 -> detalle ?: "Correo o contraseña incorrectos"
            403 -> detalle ?: "No tienes permiso para esta acción"
            400 -> detalle ?: "Solicitud inválida"
            422 -> when {
                esRegistro -> "Revisa los datos: correo válido y contraseña de 8 a 72 caracteres"
                sinSesion -> "Revisa el formato del correo y la contraseña"
                else -> "Los datos enviados no son válidos"
            }
            429 -> "Demasiados intentos. Espera un minuto e inténtalo de nuevo."
            in 500..599 -> "El servidor tuvo un problema (${e.code()}). Inténtalo más tarde."
            else -> detalle ?: "Error inesperado (${e.code()})"
        }
    }

    /** FastAPI responde {"detail": "mensaje"}; en errores de validación "detail" es una lista. */
    private fun leerDetalle(e: HttpException): String? = runCatching {
        val cuerpo = e.response()?.errorBody()?.string() ?: return null
        val detail = JsonParser.parseString(cuerpo).asJsonObject.get("detail")
        if (detail != null && detail.isJsonPrimitive) detail.asString else null
    }.getOrNull()
}
