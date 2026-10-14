package com.fossfiles.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fossfiles.app.FileItem
import com.fossfiles.app.FileViewModel
import com.fossfiles.app.TrashBin
import com.fossfiles.app.formatSize
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

private enum class TrashConfirm { EMPTY, DELETE_FOREVER }

/** Papelera local: tocar elementos para seleccionarlos y restaurarlos o borrarlos para siempre. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(viewModel: FileViewModel, onBack: () -> Unit) {
    val entries by viewModel.trashEntries
    val selected by viewModel.trashSelection
    val busy = viewModel.isLoading.value || viewModel.isWorking.value
    val dateFmt = rememberDateFormat()
    var confirm by remember { mutableStateOf<TrashConfirm?>(null) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (selected.isNotEmpty()) {
                    TopAppBar(
                        title = { Text(if (selected.size == 1) "1 seleccionado" else "${selected.size} seleccionados", maxLines = 1) },
                        navigationIcon = { IconAction(Icons.Default.Close, "Cancelar selección") { viewModel.clearTrashSelection() } },
                        actions = {
                            IconAction(Icons.Default.SelectAll, "Seleccionar todo") { viewModel.selectAllTrash() }
                            IconAction(Icons.Default.Restore, "Restaurar") { viewModel.restoreSelectedFromTrash() }
                            IconAction(Icons.Default.DeleteForever, "Eliminar definitivamente") {
                                confirm = TrashConfirm.DELETE_FOREVER
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        title = { Text("Papelera") },
                        navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", onBack) },
                        actions = {
                            if (entries.isNotEmpty()) {
                                IconAction(Icons.Default.DeleteSweep, "Vaciar papelera") { confirm = TrashConfirm.EMPTY }
                            }
                        }
                    )
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                Text(
                    "Lo que elimines se conserva aquí 30 días y luego se borra solo. Ocupa espacio en el dispositivo hasta entonces.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
                StatusLine(busy)
                if (!busy && entries.isEmpty()) EmptyMessage("La papelera está vacía")
                LazyColumn {
                    items(entries, key = { it.id }) { entry ->
                        TrashRow(
                            entry = entry,
                            selected = entry.id in selected,
                            dateFmt = dateFmt,
                            onToggle = { viewModel.toggleTrashSelection(entry) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }

        when (confirm) {
            TrashConfirm.EMPTY -> ConfirmDialog(
                title = "¿Vaciar la papelera?",
                text = "Se borrarán para siempre ${entries.size} elementos. Esta acción no se puede deshacer.",
                confirmLabel = "Vaciar",
                onConfirm = {
                    viewModel.emptyTrash()
                    confirm = null
                },
                onDismiss = { confirm = null }
            )
            TrashConfirm.DELETE_FOREVER -> ConfirmDialog(
                title = "¿Eliminar definitivamente?",
                text = "Se borrarán para siempre ${selected.size} elementos. Esta acción no se puede deshacer.",
                confirmLabel = "Eliminar",
                onConfirm = {
                    viewModel.deleteSelectedFromTrashForever()
                    confirm = null
                },
                onDismiss = { confirm = null }
            )
            null -> Unit
        }
    }
}

/** Fila de la papelera: nombre, tamaño y fecha de borrado, y de qué carpeta venía. */
@Composable
private fun TrashRow(entry: TrashBin.Entry, selected: Boolean, dateFmt: SimpleDateFormat, onToggle: () -> Unit) {
    val highlight = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
    // Un FileItem armado a mano (sin tocar el disco) solo para reusar el ícono según el tipo.
    val shown = FileItem(
        file = entry.file,
        name = entry.name,
        isDirectory = entry.isDirectory,
        sizeBytes = entry.sizeBytes,
        lastModified = entry.deletedAt
    )
    val origin = entry.originalPath?.let { File(it).parent }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlight)
            .plainClickable(onLongClick = onToggle, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.CheckCircle else iconFor(shown),
            contentDescription = if (selected) "Seleccionado" else null,
            tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val size = if (entry.isDirectory) "" else formatSize(entry.sizeBytes) + " · "
            Text(
                text = "$size${dateFmt.format(Date(entry.deletedAt))}",
                style = MaterialTheme.typography.bodySmall
            )
            if (origin != null) {
                Text(
                    text = "De: $origin",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
