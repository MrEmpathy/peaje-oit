package com.peaje.app.ui.cuenta

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.peaje.app.data.model.Usuario
import com.peaje.app.ui.theme.PeajeTheme

@Composable
fun CuentaScreen(usuario: Usuario, onCerrarSesion: () -> Unit) {
    var confirmarSalida by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Avatar(usuario)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${usuario.nombre} ${usuario.apellido}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(usuario.correo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Dato(Icons.Filled.Badge, "Patente", usuario.patente ?: "-", monoespaciado = true)
            HorizontalDivider()
            Dato(Icons.Filled.DirectionsCar, "Tipo de vehículo", usuario.tipoVehiculoNombre ?: usuario.tipoVehiculo ?: "-")
            HorizontalDivider()
            Dato(Icons.Filled.Nfc, "UID virtual (NFC)", usuario.uidNfc ?: "-", monoespaciado = true)
            HorizontalDivider()
            Dato(Icons.Filled.VerifiedUser, "Estado de la cuenta", if (usuario.activo) "Activa" else "Inactiva")
        }

        OutlinedButton(
            onClick = { confirmarSalida = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Cerrar sesión")
        }
    }

    if (confirmarSalida) {
        AlertDialog(
            onDismissRequest = { confirmarSalida = false },
            title = { Text("¿Cerrar sesión?") },
            text = { Text("Tendrás que ingresar tu correo y contraseña de nuevo.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmarSalida = false
                    onCerrarSesion()
                }) { Text("Cerrar sesión") }
            },
            dismissButton = {
                TextButton(onClick = { confirmarSalida = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun Avatar(usuario: Usuario) {
    val iniciales = (usuario.nombre.take(1) + usuario.apellido.take(1)).uppercase()
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                iniciales,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun Dato(icono: ImageVector, titulo: String, valor: String, monoespaciado: Boolean = false) {
    ListItem(
        leadingContent = { Icon(icono, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        overlineContent = { Text(titulo) },
        headlineContent = {
            Text(valor, fontFamily = if (monoespaciado) FontFamily.Monospace else null, fontWeight = FontWeight.Medium)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Preview(showBackground = true)
@Composable
private fun CuentaPreview() {
    PeajeTheme(darkTheme = false) {
        Surface {
            CuentaScreen(
                Usuario(1, "Ana", "Paz", "ana@correo.cl", "727B98FC", "BCDF12", "AUTO", "Automóvil", 4100.0, true),
                onCerrarSesion = {},
            )
        }
    }
}
