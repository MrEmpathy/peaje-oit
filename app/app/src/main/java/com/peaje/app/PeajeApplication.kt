package com.peaje.app

import android.app.Application
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.peaje.app.data.LimiteIntentosLogin
import com.peaje.app.data.PeajeRepository
import com.peaje.app.data.SessionManager
import com.peaje.app.data.remote.ApiClient
import com.peaje.app.nfc.EstadoNfc
import com.peaje.app.ui.login.LoginViewModel
import com.peaje.app.ui.pago.PagoNfcViewModel
import com.peaje.app.ui.principal.PrincipalViewModel
import com.peaje.app.ui.recarga.RecargaViewModel
import com.peaje.app.ui.registro.RegistroViewModel

/** Punto único donde se crean las dependencias compartidas de la app. */
class PeajeApplication : Application() {

    lateinit var repositorio: PeajeRepository
        private set

    private val limiteLogin by lazy { LimiteIntentosLogin(LimiteIntentosLogin.AlmacenPrefs(this)) }

    /** Fábrica para crear cualquier ViewModel de la app con viewModel(factory = ...). */
    val viewModelFactory by lazy {
        viewModelFactory {
            initializer { LoginViewModel(repositorio, limiteLogin) }
            initializer { RegistroViewModel(repositorio) }
            initializer { PrincipalViewModel(repositorio) }
            initializer { RecargaViewModel(repositorio) }
            initializer { PagoNfcViewModel(repositorio) { EstadoNfc.de(this@PeajeApplication) } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val session = SessionManager(this)
        repositorio = PeajeRepository(ApiClient.crear(token = { session.token }), session)
    }
}
