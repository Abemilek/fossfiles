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
import androidx.compose.ui.res.stringResource
import com.fossfiles.app.R
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
                    title = { Text(stringResource(R.string.settings)) },
                    navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack) }
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                SettingsHeader(stringResource(R.string.section_appearance))
                SettingRow(stringResource(R.string.setting_theme), stringResource(theme.labelRes)) { viewModel.cycleTheme() }
                SettingRow(stringResource(R.string.setting_default_view), stringResource(if (grid) R.string.value_grid else R.string.value_list)) { viewModel.toggleGrid() }
                SettingRow(stringResource(R.string.setting_hidden_files), stringResource(if (hidden) R.string.hidden_shown else R.string.hidden_not_shown)) { viewModel.toggleHidden() }

                SettingsHeader(stringResource(R.string.trash))
                SettingRow(stringResource(R.string.setting_keep_deleted), pluralText(R.plurals.keep_days_value, days, days)) { viewModel.cycleTrashDays() }

                SettingsHeader(stringResource(R.string.section_maintenance))
                SettingRow(stringResource(R.string.setting_free_memory), stringResource(R.string.setting_free_memory_desc)) { viewModel.clearThumbnailCache() }
                SettingRow(stringResource(R.string.setting_error_log), stringResource(R.string.setting_error_log_desc)) {
                    viewModel.showCrashLog()
                }

                SettingsHeader(stringResource(R.string.section_about))
                Text(
                    text = stringResource(R.string.about_version, viewModel.appVersion),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    text = stringResource(R.string.about_text),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        crash?.let { text ->
            ModalOverlay(onDismiss = { viewModel.closeCrashLog() }) {
                // Se resuelve aquí: dentro del onClick ya no hay contexto composable.
                val copiedMessage = stringResource(R.string.copied_log)
                Text(stringResource(R.string.last_error), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    PlainTextButton(stringResource(R.string.copy)) {
                        clipboard.setText(AnnotatedString(text))
                        viewModel.message.value = copiedMessage
                    }
                    PlainTextButton(stringResource(R.string.clear_log)) { viewModel.clearCrashLog() }
                    PlainTextButton(stringResource(R.string.close)) { viewModel.closeCrashLog() }
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
