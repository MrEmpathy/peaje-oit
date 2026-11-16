package com.peaje.app.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.peaje.app.data.LimiteIntentosLogin
import com.peaje.app.ui.theme.PeajeTheme

@Composable
fun LoginScreen(viewModel: LoginViewModel, onRegistrarse: () -> Unit) {
    LoginContent(
        state = viewModel.uiState,
        onCorreoChange = viewModel::onCorreoChange,
        onPasswordChange = viewModel::onPasswordChange,
        onIniciarSesion = viewModel::iniciarSesion,
        onRegistrarse = onRegistrarse,
    )
}

/** Parte visual sin ViewModel, para poder previsualizarla. */
@Composable
fun LoginContent(
    state: LoginUiState,
    onCorreoChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onIniciarSesion: () -> Unit,
    onRegistrarse: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var mostrarPassword by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(88.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Toll,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(20.dp),
                )
            }

            Text(
                text = "Peaje IoT",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Inicia sesión para continuar",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.correo,
                onValueChange = onCorreoChange,
                label = { Text("Correo electrónico") },
                leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null) },
                isError = state.correoError != null,
                supportingText = state.correoError?.let { { Text(it) } },
                singleLine = true,
                enabled = !state.cargando && !state.bloqueado,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                    autoCorrectEnabled = false,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = onPasswordChange,
                label = { Text("Contraseña") },
                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { mostrarPassword = !mostrarPassword }) {
                        Icon(
                            imageVector = if (mostrarPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (mostrarPassword) "Ocultar contraseña" else "Mostrar contraseña",
                        )
                    }
                },
                visualTransformation = if (mostrarPassword) VisualTransformation.None else PasswordVisualTransformation(),
                isError = state.passwordError != null,
                supportingText = state.passwordError?.let { { Text(it) } },
                singleLine = true,
                enabled = !state.cargando && !state.bloqueado,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    focusManager.clearFocus()
                    onIniciarSesion()
                }),
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let { mensaje ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MensajeError(mensaje)
                }
            }

            if (state.bloqueado) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MensajeError(
                        mensaje = "Superaste el máximo de ${LimiteIntentosLogin.MAX_INTENTOS} intentos. " +
                            "Podrás intentarlo de nuevo en ${formatearEspera(state.segundosBloqueo)}.",
                        icono = Icons.Filled.LockClock,
                    )
                }
            }

            Button(
                onClick = {
                    focusManager.clearFocus()
                    onIniciarSesion()
                },
                enabled = !state.cargando && !state.bloqueado,
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
                } else if (state.bloqueado) {
                    Text("Espera ${formatearEspera(state.segundosBloqueo)}", style = MaterialTheme.typography.titleMedium)
                } else {
                    Text("Iniciar sesión", style = MaterialTheme.typography.titleMedium)
                }
            }

            TextButton(onClick = onRegistrarse, enabled = !state.cargando) {
                Text("¿No tienes cuenta? Regístrate")
            }
        }
    }
}

@Composable
private fun MensajeError(mensaje: String, icono: ImageVector = Icons.Filled.ErrorOutline) {
    Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icono, contentDescription = null)
        Text(mensaje, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 45 -> "45 s", 75 -> "1:15". */
internal fun formatearEspera(segundos: Int): String =
    if (segundos < 60) "$segundos s" else "%d:%02d".format(segundos / 60, segundos % 60)

@Preview(showBackground = true, name = "Login")
@Composable
private fun LoginPreview() {
    PeajeTheme(darkTheme = false) {
        Surface { LoginContent(LoginUiState(correo = "ana@correo.cl"), {}, {}, {}, {}) }
    }
}

@Preview(showBackground = true, name = "Login con error")
@Composable
private fun LoginErrorPreview() {
    PeajeTheme(darkTheme = false) {
        Surface {
            LoginContent(
                LoginUiState(
                    correo = "ana@correo.cl",
                    password = "x",
                    error = "Correo o contraseña incorrectos. Te quedan 2 intentos.",
                ),
                {}, {}, {}, {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Login bloqueado")
@Composable
private fun LoginBloqueadoPreview() {
    PeajeTheme(darkTheme = false) {
        Surface { LoginContent(LoginUiState(correo = "ana@correo.cl", segundosBloqueo = 42), {}, {}, {}, {}) }
    }
}
