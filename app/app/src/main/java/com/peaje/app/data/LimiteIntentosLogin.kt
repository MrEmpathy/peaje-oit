package com.peaje.app.data

import android.content.Context
import androidx.core.content.edit

/**
 * Limita el inicio de sesión a [MAX_INTENTOS] intentos fallidos seguidos. Al llegar al máximo, bloquea el
 * botón durante [BLOQUEO_MS] y luego da otros [MAX_INTENTOS] intentos. El estado se guarda en el teléfono,
 * así que cerrar y reabrir la app no reinicia el contador. Un login correcto lo reinicia.
 *
 * Es una barrera de la interfaz; la protección real contra fuerza bruta es el límite del servidor
 * (LIMITE_LOGIN en api_peaje.py).
 */
class LimiteIntentosLogin(
    private val almacen: Almacen,
    private val reloj: () -> Long = System::currentTimeMillis,
) {
    interface Almacen {
        var fallidos: Int
        var bloqueadoHasta: Long
    }

    /** Milisegundos que faltan para poder volver a intentar (0 si no está bloqueado). */
    fun msRestantesBloqueo(): Long =
        // coerceAtMost: si se atrasa la hora del teléfono, el bloqueo no se alarga más de lo configurado.
        (almacen.bloqueadoHasta - reloj()).coerceIn(0, BLOQUEO_MS)

    fun estaBloqueado(): Boolean = msRestantesBloqueo() > 0

    /** Anota un intento fallido. Devuelve cuántos intentos quedan; 0 significa que se acaba de bloquear. */
    fun registrarFallo(): Int {
        val fallidos = almacen.fallidos + 1
        if (fallidos >= MAX_INTENTOS) {
            almacen.fallidos = 0
            almacen.bloqueadoHasta = reloj() + BLOQUEO_MS
            return 0
        }
        almacen.fallidos = fallidos
        return MAX_INTENTOS - fallidos
    }

    fun reiniciar() {
        almacen.fallidos = 0
        almacen.bloqueadoHasta = 0
    }

    /** SharedPreferences propias: cerrar sesión borra "sesion_peaje", pero no debe borrar el bloqueo. */
    class AlmacenPrefs(context: Context) : Almacen {
        private val prefs = context.getSharedPreferences("intentos_login", Context.MODE_PRIVATE)
        override var fallidos: Int
            get() = prefs.getInt("fallidos", 0)
            set(v) = prefs.edit { putInt("fallidos", v) }
        override var bloqueadoHasta: Long
            get() = prefs.getLong("bloqueado_hasta", 0)
            set(v) = prefs.edit { putLong("bloqueado_hasta", v) }
    }

    companion object {
        const val MAX_INTENTOS = 3
        const val BLOQUEO_MS = 60_000L
    }
}
