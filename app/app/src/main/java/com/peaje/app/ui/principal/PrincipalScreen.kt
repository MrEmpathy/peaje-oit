package com.peaje.app.ui.principal

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.peaje.app.data.model.Usuario
import com.peaje.app.ui.cuenta.CuentaScreen
import com.peaje.app.ui.inicio.InicioScreen
import com.peaje.app.ui.pago.EstadoPago
import com.peaje.app.ui.pago.PagoNfcSheet
import com.peaje.app.ui.pago.PagoNfcViewModel
import com.peaje.app.ui.recarga.RecargaScreen
import com.peaje.app.ui.recarga.RecargaViewModel

/** Pestañas de la barra de navegación inferior. */
enum class Pestana(
    val ruta: String,
    val etiqueta: String,
    val icono: ImageVector,
    val iconoSeleccionado: ImageVector,
) {
    INICIO("inicio", "Inicio", Icons.Outlined.Home, Icons.Filled.Home),
    RECARGAR("recargar", "Recargar", Icons.Outlined.AccountBalanceWallet, Icons.Filled.AccountBalanceWallet),
    CUENTA("cuenta", "Cuenta", Icons.Outlined.Person, Icons.Filled.Person),
}

@Composable
fun PrincipalScreen(
    usuario: Usuario,
    viewModel: PrincipalViewModel,
    viewModelFactory: ViewModelProvider.Factory,
) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.mensajes.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        bottomBar = { BarraInferior(navController) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Pestana.INICIO.ruta,
            modifier = Modifier.padding(padding),
        ) {
            composable(Pestana.INICIO.ruta) {
                // Cada vez que se entra a Inicio se actualiza el saldo desde el servidor.
                LaunchedEffect(Unit) { viewModel.refrescar() }
                val pagoViewModel: PagoNfcViewModel = viewModel(factory = viewModelFactory)
                var mostrarPago by rememberSaveable { mutableStateOf(false) }

                InicioScreen(
                    usuario = usuario,
                    refrescando = viewModel.refrescando,
                    onRefrescar = viewModel::refrescar,
                    onPagarNfc = { mostrarPago = true },
                )

                if (mostrarPago) {
                    val context = LocalContext.current
                    LaunchedEffect(Unit) { pagoViewModel.iniciar() }
                    // Al volver de los ajustes de NFC se revisa de nuevo si quedó encendido.
                    LifecycleResumeEffect(Unit) {
                        if (pagoViewModel.estado == EstadoPago.NfcApagado) pagoViewModel.iniciar(forzar = true)
                        onPauseOrDispose { }
                    }
                    PagoNfcSheet(
                        estado = pagoViewModel.estado,
                        onCerrar = {
                            pagoViewModel.cancelar()
                            mostrarPago = false
                        },
                        onReintentar = { pagoViewModel.iniciar(forzar = true) },
                        onAbrirAjustesNfc = { context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) },
                    )
                }
            }
            composable(Pestana.RECARGAR.ruta) {
                val recargaViewModel: RecargaViewModel = viewModel(factory = viewModelFactory)
                RecargaScreen(saldoActual = usuario.saldo, viewModel = recargaViewModel)
            }
            composable(Pestana.CUENTA.ruta) {
                CuentaScreen(usuario = usuario, onCerrarSesion = viewModel::cerrarSesion)
            }
        }
    }
}

@Composable
private fun BarraInferior(navController: NavHostController) {
    val entradaActual by navController.currentBackStackEntryAsState()
    val destinoActual = entradaActual?.destination

    NavigationBar {
        Pestana.entries.forEach { pestana ->
            val seleccionada = destinoActual?.hierarchy?.any { it.route == pestana.ruta } == true
            NavigationBarItem(
                selected = seleccionada,
                onClick = { navController.irA(pestana) },
                icon = {
                    Icon(
                        imageVector = if (seleccionada) pestana.iconoSeleccionado else pestana.icono,
                        contentDescription = null,
                    )
                },
                label = { Text(pestana.etiqueta) },
            )
        }
    }
}

/** Navega a una pestaña sin apilar pantallas repetidas y conservando el estado de cada una. */
private fun NavHostController.irA(pestana: Pestana) {
    navigate(pestana.ruta) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
