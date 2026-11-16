package com.peaje.app.ui.registro

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.peaje.app.data.model.TipoVehiculo
import com.peaje.app.ui.theme.PeajeTheme

@Composable
fun RegistroScreen(viewModel: RegistroViewModel, onVolver: () -> Unit) {
    BackHandler(enabled = !viewModel.uiState.cargando, onBack = onVolver)
    RegistroContent(
        state = viewModel.uiState,
        onCambio = viewModel::onCambio,
        onRegistrar = viewModel::registrar,
        onReintentarTipos = viewModel::cargarTipos,
        onVolver = onVolver,
    )
}

/** Parte visual sin ViewModel, para poder previsualizarla. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegistroContent(
    state: RegistroUiState,
    onCambio: (Campo, String) -> Unit,
    onRegistrar: () -> Unit,
    onReintentarTipos: () -> Unit,
    onVolver: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var mostrarPassword by rememberSaveable { mutableStateOf(false) }
    val habilitado = !state.cargando
    val registrar = {
        focusManager.clearFocus()
        onRegistrar()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onVolver, enabled = habilitado) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver al inicio de sesión")
                }
                Text("Crear cuenta", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Text(
                "Registra tus datos y tu vehículo para pagar el peaje con tu celular o tarjeta.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Seccion("Tus datos")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CampoTexto(
                    valor = state.nombre, etiqueta = "Nombre", campo = Campo.NOMBRE, state = state, onCambio = onCambio,
                    icono = Icons.Filled.Person, capitalizacion = KeyboardCapitalization.Words,
                    modifier = Modifier.weight(1f),
                )
                CampoTexto(
                    valor = state.apellido, etiqueta = "Apellido", campo = Campo.APELLIDO, state = state, onCambio = onCambio,
                    capitalizacion = KeyboardCapitalization.Words, modifier = Modifier.weight(1f),
                )
            }
            CampoTexto(
                valor = state.correo, etiqueta = "Correo electrónico", campo = Campo.CORREO, state = state,
                onCambio = onCambio, icono = Icons.Filled.Email, teclado = KeyboardType.Email,
            )

            val ojo: @Composable () -> Unit = {
                IconButton(onClick = { mostrarPassword = !mostrarPassword }) {
                    Icon(
                        imageVector = if (mostrarPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (mostrarPassword) "Ocultar contraseña" else "Mostrar contraseña",
                    )
                }
            }
            val transformacion = if (mostrarPassword) VisualTransformation.None else PasswordVisualTransformation()
            CampoTexto(
                valor = state.password, etiqueta = "Contraseña", campo = Campo.PASSWORD, state = state,
                onCambio = onCambio, icono = Icons.Filled.Lock, teclado = KeyboardType.Password,
                ayuda = "Mínimo ${RegistroViewModel.MIN_PASSWORD} caracteres",
                trailing = ojo, transformacion = transformacion,
            )
            CampoTexto(
                valor = state.confirmarPassword, etiqueta = "Repetir contraseña", campo = Campo.CONFIRMAR, state = state,
                onCambio = onCambio, icono = Icons.Filled.Lock, teclado = KeyboardType.Password,
                transformacion = transformacion,
            )

            Seccion("Tu vehículo")
            CampoTexto(
                valor = state.patente, etiqueta = "Patente", campo = Campo.PATENTE, state = state, onCambio = onCambio,
                icono = Icons.Filled.Badge, capitalizacion = KeyboardCapitalization.Characters,
                ayuda = "Ej. BCDF12 o AB1234", imeAction = ImeAction.Done, onDone = { focusManager.clearFocus() },
            )

            Text("Tipo de vehículo", style = MaterialTheme.typography.labelLarge)
            when {
                state.cargandoTipos -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Cargando tipos de vehículo…", style = MaterialTheme.typography.bodyMedium)
                }
                state.errorTipos != null -> Aviso(state.errorTipos) {
                    TextButton(onClick = onReintentarTipos) { Text("Reintentar") }
                }
                else -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    state.tipos.forEach { tipo ->
                        FilterChip(
                            selected = state.tipoVehiculo == tipo.codigo,
                            onClick = { onCambio(Campo.TIPO, tipo.codigo) },
                            label = { Text(tipo.nombre) },
                            leadingIcon = if (state.tipoVehiculo == tipo.codigo) {
                                { Icon(Icons.Filled.DirectionsCar, contentDescription = null, Modifier.size(18.dp)) }
                            } else null,
                            enabled = habilitado,
                        )
                    }
                }
            }
            state.errores[Campo.TIPO]?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            state.error?.let { Aviso(it) }

            Button(
                onClick = registrar,
                enabled = habilitado,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.cargando) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Crear cuenta", style = MaterialTheme.typography.titleMedium)
                }
            }
            TextButton(
                onClick = onVolver,
                enabled = habilitado,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("¿Ya tienes cuenta? Inicia sesión")
            }
        }
    }
}

@Composable
private fun Seccion(titulo: String) {
    Text(
        titulo,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun CampoTexto(
    valor: String,
    etiqueta: String,
    campo: Campo,
    state: RegistroUiState,
    onCambio: (Campo, String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    icono: ImageVector? = null,
    teclado: KeyboardType = KeyboardType.Text,
    capitalizacion: KeyboardCapitalization = KeyboardCapitalization.None,
    ayuda: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    transformacion: VisualTransformation = VisualTransformation.None,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    val error = state.errores[campo]
    OutlinedTextField(
        value = valor,
        onValueChange = { onCambio(campo, it) },
        label = { Text(etiqueta) },
        leadingIcon = icono?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = trailing,
        visualTransformation = transformacion,
        isError = error != null,
        supportingText = (error ?: ayuda)?.let { { Text(it) } },
        singleLine = true,
        enabled = !state.cargando,
        keyboardOptions = KeyboardOptions(
            keyboardType = teclado,
            capitalization = capitalizacion,
            imeAction = imeAction,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = modifier,
    )
}

@Composable
private fun Aviso(mensaje: String, accion: (@Composable () -> Unit)? = null) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.ErrorOutline, contentDescription = null)
            Text(mensaje, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            accion?.invoke()
        }
    }
}

private val TIPOS_EJEMPLO = listOf(
    TipoVehiculo("MOTO", "Motocicleta", null),
    TipoVehiculo("AUTO", "Automóvil", null),
    TipoVehiculo("CAMIONETA", "Camioneta", null),
    TipoVehiculo("BUS", "Bus", null),
    TipoVehiculo("CAMION", "Camión", null),
)

@Preview(showBackground = true, name = "Registro", heightDp = 900)
@Composable
private fun RegistroPreview() {
    PeajeTheme(darkTheme = false) {
        Surface {
            RegistroContent(
                RegistroUiState(nombre = "Ana", correo = "ana@correo", tipos = TIPOS_EJEMPLO, tipoVehiculo = "AUTO",
                    errores = mapOf(Campo.CORREO to "Correo no válido")),
                { _, _ -> }, {}, {}, {},
            )
        }
    }
}
