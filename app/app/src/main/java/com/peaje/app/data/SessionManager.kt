package com.peaje.app.data

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.peaje.app.data.model.Usuario

/**
 * Guarda la sesión (token JWT + datos del usuario) en SharedPreferences,
 * como indica la arquitectura del proyecto.
 */
class SessionManager(context: Context) {

    private val prefs = context.getSharedPreferences("sesion_peaje", Context.MODE_PRIVATE)
    private val gson = Gson()

    val token: String?
        get() = prefs.getString(KEY_TOKEN, null)

    fun guardar(token: String, usuario: Usuario) {
        prefs.edit {
            putString(KEY_TOKEN, token)
            putString(KEY_USUARIO, gson.toJson(usuario))
        }
    }

    /** Actualiza sólo los datos del usuario (ej. saldo nuevo), manteniendo el token. */
    fun actualizarUsuario(usuario: Usuario) {
        prefs.edit { putString(KEY_USUARIO, gson.toJson(usuario)) }
    }

    fun usuarioGuardado(): Usuario? {
        if (token == null) return null
        val json = prefs.getString(KEY_USUARIO, null) ?: return null
        return runCatching { gson.fromJson(json, Usuario::class.java) }.getOrNull()
    }

    fun cerrarSesion() {
        prefs.edit { clear() }
    }

    private companion object {
        const val KEY_TOKEN = "access_token"
        const val KEY_USUARIO = "usuario"
    }
}
