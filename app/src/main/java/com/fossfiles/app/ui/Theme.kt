package com.fossfiles.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

// Negro puro (#000000) en fondo y superficies: en pantallas AMOLED esos
// píxeles se apagan del todo en vez de emitir un gris oscuro, que es lo que
// realmente ahorra batería (no es solo estético).
private val AmoledDarkColors = darkColorScheme(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF121212),
    primary = Color(0xFF8AB4F8),
    onBackground = Color(0xFFE3E3E3),
    onSurface = Color(0xFFE3E3E3)
)

private val LightColors = lightColorScheme()

// Política de cero animaciones: la app no anima nada por sí misma (sin transiciones,
// sin ripple, sin hojas ni menús que se deslizan). Aquí se apaga además el efecto de
// "estirar" al llegar al final de una lista, que es una animación del sistema.
// LocalOverscrollConfiguration está deprecado, pero su reemplazo (LocalOverscrollFactory)
// solo existe en Compose 1.8+; se silencia el aviso manteniendo el comportamiento actual.
@Suppress("DEPRECATION")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FossFilesTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) AmoledDarkColors else LightColors
    MaterialTheme(colorScheme = colorScheme) {
        CompositionLocalProvider(LocalOverscrollConfiguration provides null, content = content)
    }
}
