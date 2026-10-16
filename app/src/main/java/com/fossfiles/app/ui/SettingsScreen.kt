package com.fossfiles.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fossfiles.app.FileViewModel

/**
 * Ajustes. Cada opción cambia con un toque (pasa a la siguiente), sin menús desplegables animados.
 * Todo se guarda solo en el dispositivo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: FileViewModel, onBack: () -> Unit) {
    val theme by viewModel.themeMode
    val hidden by viewModel.showHidden
    val grid by viewModel.gridView
    val days by viewModel.trashDays
    val crash by viewModel.crashText
    val clipboard = LocalClipboardManager.current

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ajustes") },
                    navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", onBack) }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                SettingsHeader("Apariencia")
                SettingRow("Tema", theme.label) { viewModel.cycleTheme() }
                SettingRow("Vista predeterminada", if (grid) "Cuadrícula" else "Lista") { viewModel.toggleGrid() }
                SettingRow("Archivos ocultos", if (hidden) "Se muestran" else "Se ocultan") { viewModel.toggleHidden() }

                SettingsHeader("Papelera")
                SettingRow("Conservar eliminados", "$days días (toca para cambiar)") { viewModel.cycleTrashDays() }

                SettingsHeader("Mantenimiento")
                SettingRow("Liberar memoria", "Vacía la caché de miniaturas") { viewModel.clearThumbnailCache() }
                SettingRow("Registro de errores", "Ver el último error de la app (se queda en tu dispositivo)") {
                    viewModel.showCrashLog()
                }

                SettingsHeader("Acerca de")
                Text(
                    text = "FossFiles ${viewModel.appVersion}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    text = "Software libre (GPL-3.0). Sin Internet, sin telemetría, sin anuncios y sin servicios de Google.\n\n" +
                        "La app no tiene permiso de Internet: es Android quien le impide conectarse a cualquier red. " +
                        "Puedes comprobarlo en Ajustes de Android → Apps → FossFiles → Permisos.\n\n" +
                        "Desde Android 11 las carpetas Android/data y Android/obb están protegidas por el sistema y " +
                        "ninguna app de archivos puede abrirlas.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        crash?.let { text ->
            ModalOverlay(onDismiss = { viewModel.closeCrashLog() }) {
                Text("Último error", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    PlainTextButton("Copiar") {
                        clipboard.setText(AnnotatedString(text))
                        viewModel.message.value = "Copiado: pégalo en un reporte si quieres"
                    }
                    PlainTextButton("Borrar registro") { viewModel.clearCrashLog() }
                    PlainTextButton("Cerrar") { viewModel.closeCrashLog() }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .plainClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
