package com.peaje.app

import android.content.ComponentName
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.peaje.app.nfc.PeajeHceService
import com.peaje.app.ui.login.LoginScreen
import com.peaje.app.ui.login.LoginViewModel
import com.peaje.app.ui.principal.PrincipalScreen
import com.peaje.app.ui.principal.PrincipalViewModel
import com.peaje.app.ui.registro.RegistroScreen
import com.peaje.app.ui.registro.RegistroViewModel
import com.peaje.app.ui.theme.PeajeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as PeajeApplication

        setContent {
            PeajeTheme {
                // Sin sesión -> login (o registro). Con sesión -> pantallas con barra inferior.
                // Login, logout y sesión expirada cambian este valor y la pantalla se actualiza sola.
                val usuario by app.repositorio.usuario.collectAsStateWithLifecycle()
                val actual = usuario

                if (actual == null) {
                    // Dentro de este bloque: al cerrar sesión se vuelve siempre al login, no al registro.
                    var enRegistro by rememberSaveable { mutableStateOf(false) }
                    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                        Box(Modifier.padding(padding)) {
                            if (enRegistro) {
                                val registroViewModel: RegistroViewModel = viewModel(factory = app.viewModelFactory)
                                RegistroScreen(registroViewModel, onVolver = { enRegistro = false })
                            } else {
                                val loginViewModel: LoginViewModel = viewModel(factory = app.viewModelFactory)
                                LoginScreen(loginViewModel, onRegistrarse = { enRegistro = true })
                            }
                        }
                    }
                } else {
                    val principalViewModel: PrincipalViewModel = viewModel(factory = app.viewModelFactory)
                    PrincipalScreen(actual, principalViewModel, app.viewModelFactory)
                }
            }
        }
    }

    // Con la app en primer plano, nuestro servicio HCE tiene prioridad para su AID.
    override fun onResume() {
        super.onResume()
        emulacionNfc()?.let { runCatching { it.setPreferredService(this, ComponentName(this, PeajeHceService::class.java)) } }
    }

    override fun onPause() {
        super.onPause()
        emulacionNfc()?.let { runCatching { it.unsetPreferredService(this) } }
    }

    private fun emulacionNfc(): CardEmulation? {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) return null
        val adaptador = NfcAdapter.getDefaultAdapter(this) ?: return null
        return runCatching { CardEmulation.getInstance(adaptador) }.getOrNull()
    }
}
