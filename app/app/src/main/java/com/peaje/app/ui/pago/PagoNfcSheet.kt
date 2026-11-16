package com.peaje.app.ui.pago

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Contactless
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagoNfcSheet(
    estado: EstadoPago,
    onCerrar: () -> Unit,
    onReintentar: () -> Unit,
    onAbrirAjustesNfc: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current

    // HCE sólo funciona con la pantalla encendida: se mantiene así mientras la hoja esté abierta.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val leido = (estado as? EstadoPago.Listo)?.leido == true
    LaunchedEffect(leido, estado is EstadoPago.Resultado) {
        if (leido || estado is EstadoPago.Resultado) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    ModalBottomSheet(onDismissRequest = onCerrar, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (estado) {
                EstadoPago.Preparando -> {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                    Titulo("Preparando el pago…")
                }

                EstadoPago.NfcNoSoportado -> {
                    IconoGrande(Icons.Filled.ErrorOutline, error = true)
                    Titulo("Tu teléfono no puede pagar con NFC")
                    Detalle("Puedes pagar con una tarjeta física vinculada a tu cuenta.")
                    BotonPrincipal("Entendido", onCerrar)
                }

                EstadoPago.NfcApagado -> {
                    IconoGrande(Icons.Filled.Nfc)
                    Titulo("Activa el NFC")
                    Detalle("Para pagar acercando el teléfono, enciende el NFC en los ajustes.")
                    BotonPrincipal("Abrir ajustes de NFC", onAbrirAjustesNfc)
                    BotonSecundario("Cancelar", onCerrar)
                }

                is EstadoPago.Listo -> if (!estado.leido) {
                    AnimacionNfc()
                    Titulo("Acerca tu teléfono al lector")
                    Detalle("Mantén la pantalla encendida y desbloqueada hasta que se abra la barrera.")
                    Text(
                        text = "Código válido por ${formatearTiempo(estado.segundosRestantes)} · se renueva solo",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BotonSecundario("Cancelar", onCerrar)
                } else {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                    Titulo("Leído por el lector")
                    Detalle("Esperando la confirmación del cobro…")
                    BotonSecundario("Cerrar", onCerrar)
                }

                is EstadoPago.Resultado -> {
                    IconoGrande(
                        if (estado.aprobado) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                        error = !estado.aprobado,
                    )
                    Titulo(estado.titulo)
                    Detalle(estado.detalle)
                    BotonPrincipal("Listo", onCerrar)
                }

                is EstadoPago.Error -> {
                    IconoGrande(Icons.Filled.ErrorOutline, error = true)
                    Titulo("No se pudo preparar el pago")
                    Detalle(estado.mensaje)
                    BotonPrincipal("Reintentar", onReintentar)
                    BotonSecundario("Cancelar", onCerrar)
                }
            }
        }
    }
}

/** Círculos que "laten" alrededor del ícono sin contacto. */
@Composable
private fun AnimacionNfc() {
    val transicion = rememberInfiniteTransition(label = "nfc")
    val escala by transicion.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "escala",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            modifier = Modifier.size(150.dp).scale(escala),
        ) {}
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(104.dp)) {
            Icon(
                Icons.Filled.Contactless,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

@Composable
private fun IconoGrande(icono: ImageVector, error: Boolean = false) {
    Icon(
        icono,
        contentDescription = null,
        tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(80.dp),
    )
}

@Composable
private fun Titulo(texto: String) = Text(
    texto,
    style = MaterialTheme.typography.headlineSmall,
    fontWeight = FontWeight.Bold,
    textAlign = TextAlign.Center,
)

@Composable
private fun Detalle(texto: String) = Text(
    texto,
    style = MaterialTheme.typography.bodyLarge,
    textAlign = TextAlign.Center,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)

@Composable
private fun BotonPrincipal(texto: String, onClick: () -> Unit) =
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(texto) }

@Composable
private fun BotonSecundario(texto: String, onClick: () -> Unit) =
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(texto) }

private fun formatearTiempo(segundos: Int) = "%d:%02d".format(segundos / 60, segundos % 60)
