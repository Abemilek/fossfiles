package com.fossfiles.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

@Composable
fun FossFilesTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        darkTheme -> AmoledDarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
