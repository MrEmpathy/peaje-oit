package com.peaje.app.ui.recarga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.peaje.app.data.model.RecargaResponse
import com.peaje.app.ui.formatearPesos
import com.peaje.app.ui.theme.PeajeTheme

@Composable
fun RecargaScreen(saldoActual: Double, viewModel: RecargaViewModel) {
    RecargaContent(
        saldoActual = saldoActual,
        state = viewModel.uiState,
        onMontoChange = viewModel::onMontoChange,
        onMontoRapido = viewModel::seleccionarMonto,
        onRecargar = viewModel::recargar,
    )
}

@Composable
fun RecargaContent(
    saldoActual: Double,
    state: RecargaUiState,
    onMontoChange: (String) -> Unit,
    onMontoRapido: (Long) -> Unit,
    onRecargar: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val recargar = {
        focusManager.clearFocus()
        onRecargar()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Recargar saldo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(20.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Saldo actual", style = MaterialTheme.typography.labelLarge)
                Text(
                    formatearPesos(saldoActual),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        OutlinedTextField(
            value = state.montoTexto,
            onValueChange = onMontoChange,
            label = { Text("Monto a recargar") },
            prefix = { Text("$ ") },
            supportingText = { Text("Máximo ${formatearPesos(RecargaViewModel.MAXIMO.toDouble())} por recarga") },
            singleLine = true,
            enabled = !state.cargando,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { recargar() }),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Montos rápidos", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            RecargaViewModel.MONTOS_RAPIDOS.forEach { monto ->
                FilterChip(
                    selected = state.monto == monto,
                    onClick = { onMontoRapido(monto) },
                    label = { Text(formatearPesos(monto.toDouble())) },
                    enabled = !state.cargando,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        state.error?.let { Aviso(it, Icons.Filled.ErrorOutline, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) }
        state.ultimaRecarga?.let { r ->
            Aviso(
                mensaje = "Recarga exitosa de ${formatearPesos(r.montoRecargado)}. Nuevo saldo: ${formatearPesos(r.saldoActual)}",
                icono = Icons.Filled.CheckCircle,
                fondo = MaterialTheme.colorScheme.primaryContainer,
                texto = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Button(
            onClick = recargar,
            enabled = !state.cargando,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state.cargando) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                val texto = if (state.monto > 0) "Recargar ${formatearPesos(state.monto.toDouble())}" else "Recargar"
                Text(texto, style = MaterialTheme.typography.titleMedium)
            }
        }

        Text(
            text = "Recarga de prueba: no se realiza ningún cobro real.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Aviso(mensaje: String, icono: ImageVector, fondo: Color, texto: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = fondo, contentColor = texto),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icono, contentDescription = null)
            Text(mensaje, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RecargaPreview() {
    PeajeTheme(darkTheme = false) {
        Surface {
            RecargaContent(
                saldoActual = 4100.0,
                state = RecargaUiState(montoTexto = "5000", ultimaRecarga = RecargaResponse("ok", 4100.0, 5000.0, 9100.0)),
                onMontoChange = {}, onMontoRapido = {}, onRecargar = {},
            )
        }
    }
}
