package com.peaje.app.data.model

import com.google.gson.annotations.SerializedName

/** Cuerpo de POST /login */
data class LoginRequest(
    val correo: String,
    val password: String,
)

/** Cuerpo de POST /registro */
data class RegistroRequest(
    val nombre: String,
    val apellido: String,
    val correo: String,
    val password: String,
    val patente: String,
    @SerializedName("tipo_vehiculo") val tipoVehiculo: String,
)

/** Un tipo de vehículo de GET /tipos-vehiculo (ej. codigo = "AUTO", nombre = "Automóvil"). */
data class TipoVehiculo(
    val codigo: String,
    val nombre: String,
    val descripcion: String?,
)

data class TiposVehiculoResponse(
    @SerializedName("tipos_vehiculo") val tiposVehiculo: List<TipoVehiculo>,
)

/** Respuesta de POST /login (y de POST /registro) */
data class LoginResponse(
    val mensaje: String?,
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String?,
    val usuario: Usuario,
)

data class Usuario(
    val id: Int,
    val nombre: String,
    val apellido: String,
    val correo: String,
    @SerializedName("uid_nfc") val uidNfc: String?,
    val patente: String?,
    @SerializedName("tipo_vehiculo") val tipoVehiculo: String?,
    @SerializedName("tipo_vehiculo_nombre") val tipoVehiculoNombre: String?,
    val saldo: Double,
    val activo: Boolean,
)

/** Respuesta de GET /usuario/saldo: igual a Usuario, pero el id viene como "usuario_id". */
data class SaldoResponse(
    @SerializedName("usuario_id") val usuarioId: Int,
    val nombre: String,
    val apellido: String,
    val correo: String,
    @SerializedName("uid_nfc") val uidNfc: String?,
    val patente: String?,
    @SerializedName("tipo_vehiculo") val tipoVehiculo: String?,
    @SerializedName("tipo_vehiculo_nombre") val tipoVehiculoNombre: String?,
    val saldo: Double,
    val activo: Boolean,
) {
    fun aUsuario() = Usuario(
        id = usuarioId, nombre = nombre, apellido = apellido, correo = correo, uidNfc = uidNfc,
        patente = patente, tipoVehiculo = tipoVehiculo, tipoVehiculoNombre = tipoVehiculoNombre,
        saldo = saldo, activo = activo,
    )
}

/** Cuerpo de POST /usuario/recargar (pesos enteros) */
data class RecargaRequest(val monto: Long)

/** Respuesta de POST /usuario/token-nfc: token de un solo uso para entregar al lector por HCE. */
data class TokenNfcResponse(
    val token: String,
    val bytes: Int?,
    val expira: String?,
    @SerializedName("valido_por_segundos") val validoPorSegundos: Int,
)

/** Un paso por el peaje (GET /usuario/historial). */
data class Transaccion(
    val id: Long,
    val metodo: String?,
    val garita: String?,
    val carril: String?,
    val patente: String?,
    @SerializedName("tipo_vehiculo") val tipoVehiculo: String?,
    val saldo: Double?,
    @SerializedName("tarifa_cobrada") val tarifaCobrada: Double,
    @SerializedName("saldo_restante") val saldoRestante: Double?,
    val estado: String,
    @SerializedName("fecha_hora") val fechaHora: String?,
)

data class HistorialResponse(val historial: List<Transaccion>)

/** Respuesta de POST /usuario/recargar */
data class RecargaResponse(
    val mensaje: String?,
    @SerializedName("saldo_anterior") val saldoAnterior: Double,
    @SerializedName("monto_recargado") val montoRecargado: Double,
    @SerializedName("saldo_actual") val saldoActual: Double,
)
