package com.peaje.app.ui.inicio

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.peaje.app.data.model.Usuario
import com.peaje.app.ui.formatearPesos
import com.peaje.app.ui.theme.PeajeTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InicioScreen(
    usuario: Usuario,
    refrescando: Boolean,
    onRefrescar: () -> Unit,
    onPagarNfc: () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refrescando,
        onRefresh = onRefrescar,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(
                    text = "Hola, ${usuario.nombre}",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Desliza hacia abajo para actualizar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TarjetaSaldo(saldo = usuario.saldo, onPagarNfc = onPagarNfc)

            if (usuario.patente != null) {
                TarjetaVehiculo(
                    patente = usuario.patente,
                    tipo = usuario.tipoVehiculoNombre ?: usuario.tipoVehiculo ?: "-",
                )
            }
        }
    }
}

/** Tarjeta de saldo: al tocarla se inicia el pago por NFC. */
@Composable
private fun TarjetaSaldo(saldo: Double, onPagarNfc: () -> Unit) {
    Card(
        onClick = onPagarNfc,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saldo disponible", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.Nfc, contentDescription = null)
            }
            Text(
                text = formatearPesos(saldo),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Contactless, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Toca para pagar con NFC", style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun TarjetaVehiculo(patente: String, tipo: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Filled.DirectionsCar,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tu vehículo", style = MaterialTheme.typography.labelLarge)
                Text(tipo, style = MaterialTheme.typography.bodyLarge)
            }
            Placa(patente)
        }
    }
}

/** Patente con aspecto de placa. */
@Composable
private fun Placa(patente: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Text(
            text = patente,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun InicioPreview() {
    PeajeTheme(darkTheme = false) {
        Surface {
            InicioScreen(
                Usuario(1, "Ana", "Paz", "ana@correo.cl", "727B98FC", "BCDF12", "AUTO", "Automóvil", 4100.0, true),
                refrescando = false, onRefrescar = {}, onPagarNfc = {},
            )
        }
    }
}
