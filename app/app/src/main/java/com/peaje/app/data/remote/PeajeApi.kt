package com.peaje.app.data.remote

import com.peaje.app.BuildConfig
import com.peaje.app.data.model.LoginRequest
import com.peaje.app.data.model.LoginResponse
import com.peaje.app.data.model.RecargaRequest
import com.peaje.app.data.model.RegistroRequest
import com.peaje.app.data.model.TiposVehiculoResponse
import com.peaje.app.data.model.RecargaResponse
import com.peaje.app.data.model.HistorialResponse
import com.peaje.app.data.model.SaldoResponse
import com.peaje.app.data.model.TokenNfcResponse
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/** Endpoints de la API REST del peaje (api_peaje.py). */
interface PeajeApi {

    @POST("login")
    suspend fun login(@Body body: LoginRequest): LoginResponse

    @POST("registro")
    suspend fun registro(@Body body: RegistroRequest): LoginResponse

    @GET("tipos-vehiculo")
    suspend fun tiposVehiculo(): TiposVehiculoResponse

    @GET("usuario/saldo")
    suspend fun saldo(): SaldoResponse

    @POST("usuario/recargar")
    suspend fun recargar(@Body body: RecargaRequest): RecargaResponse

    @POST("usuario/token-nfc")
    suspend fun tokenNfc(): TokenNfcResponse

    @GET("usuario/historial")
    suspend fun historial(@Query("limite") limite: Int): HistorialResponse
}

object ApiClient {

    /** @param token entrega el JWT actual (o null); se agrega como "Authorization: Bearer" a cada petición. */
    fun crear(baseUrl: String = BuildConfig.API_BASE_URL, token: () -> String?): PeajeApi {
        val logging = HttpLoggingInterceptor().apply {
            // BASIC: sólo método, URL y código. Nunca BODY, para no escribir contraseñas ni tokens en el log.
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        val autorizacion = Interceptor { chain ->
            val jwt = token()
            val request = if (jwt != null && chain.request().header("Authorization") == null) {
                chain.request().newBuilder().header("Authorization", "Bearer $jwt").build()
            } else {
                chain.request()
            }
            chain.proceed(request)
        }
        val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(autorizacion)
            .addInterceptor(logging)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PeajeApi::class.java)
    }
}
