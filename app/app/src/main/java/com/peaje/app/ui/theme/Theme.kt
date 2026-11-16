package com.peaje.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AzulPeaje = Color(0xFF0B5CAD)
private val AzulPeajeClaro = Color(0xFF9CC7FF)
private val Ambar = Color(0xFFFFB300)

private val ColoresClaros = lightColorScheme(
    primary = AzulPeaje,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E5FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Ambar,
    onSecondary = Color(0xFF261A00),
    background = Color(0xFFF7F9FD),
    surface = Color.White,
)

private val ColoresOscuros = darkColorScheme(
    primary = AzulPeajeClaro,
    onPrimary = Color(0xFF003063),
    primaryContainer = Color(0xFF00468C),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Ambar,
    onSecondary = Color(0xFF261A00),
    background = Color(0xFF111318),
    surface = Color(0xFF1A1C21),
)

@Composable
fun PeajeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) ColoresOscuros else ColoresClaros,
        content = content,
    )
}
