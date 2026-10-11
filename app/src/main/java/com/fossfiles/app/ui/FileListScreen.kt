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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fossfiles.app.FileItem
import com.fossfiles.app.FileKind
import com.fossfiles.app.FileViewModel
import com.fossfiles.app.SortMode
import com.fossfiles.app.formatSize
import com.fossfiles.app.kind
import com.fossfiles.app.openFileExternally
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SelectionDialog { RENAME, DELETE }

/** Navegador de carpetas: buscar, ordenar, crear carpeta y pegar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(viewModel: FileViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentDir by viewModel.currentDir
    val items by viewModel.items
    val searchQuery by viewModel.searchQuery
    val clipboard by viewModel.clipboard
    val selection by viewModel.selection
    val busy = viewModel.isLoading.value || viewModel.isWorking.value

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<SelectionDialog?>(null) }

    val selected = items.filter { it.file.absolutePath in selection }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (selection.isNotEmpty()) {
                    SelectionBar(
                        count = selection.size,
                        onClose = { viewModel.clearSelection() },
                        onSelectAll = { viewModel.selectAll() },
                        onCopy = { viewModel.copySelectedToClipboard() },
                        onCut = { viewModel.cutSelectedToClipboard() },
                        onRename = if (selected.size == 1) ({ dialog = SelectionDialog.RENAME }) else null,
                        onDelete = { dialog = SelectionDialog.DELETE }
                    )
                } else {
                    Column {
                        TopAppBar(
                            title = { Text(currentDir.name.ifBlank { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            navigationIcon = {
                                IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") { if (!viewModel.goUp()) onBack() }
                            },
                            actions = {
                                if (clipboard.isNotEmpty()) {
                                    IconAction(Icons.Default.ContentPaste, "Pegar ${clipboard.size}") { viewModel.pasteHere() }
                                }
                                IconAction(Icons.AutoMirrored.Filled.Sort, "Ordenar") { showSort = !showSort }
                                IconAction(Icons.Default.CreateNewFolder, "Nueva carpeta") { showNewFolderDialog = true }
                            }
                        )
                        PlainTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearch(it) },
                            placeholder = "Buscar en esta carpeta...",
                            leadingIcon = Icons.Default.Search,
                            onClear = { viewModel.setSearch("") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                        if (showSort) {
                            SortRow(current = viewModel.sortMode.value) {
                                viewModel.setSort(it)
                                showSort = false
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                StatusLine(busy)
                if (!busy && items.isEmpty()) {
                    EmptyMessage(if (searchQuery.isNotBlank()) "Sin resultados" else "Carpeta vacía")
                }
                FileList(
                    items = items,
                    selection = selection,
                    onOpen = { item ->
                        if (item.isDirectory) viewModel.openDirectory(item.file)
                        else openFileExternally(context, item.file)?.let { viewModel.message.value = it }
                    },
                    onToggle = { viewModel.toggleSelection(it) }
                )
            }
        }

        if (showNewFolderDialog) {
            NameInputDialog(
                title = "Nueva carpeta",
                initialValue = "",
                onConfirm = {
                    viewModel.createFolder(it)
                    showNewFolderDialog = false
                },
                onDismiss = { showNewFolderDialog = false }
            )
        }
        SelectionDialogs(viewModel, selected, dialog) { dialog = null }
    }
}

/**
 * Vista plana para las categorías del Home (Fotos, Videos, Audio, Descargas,
 * Documentos): no navega a subcarpetas, solo lista los archivos ya escaneados.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryListScreen(viewModel: FileViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val items by viewModel.items
    val title by viewModel.categoryTitle
    val selection by viewModel.selection
    val busy = viewModel.isLoading.value || viewModel.isWorking.value
    var dialog by remember { mutableStateOf<SelectionDialog?>(null) }

    val selected = items.filter { it.file.absolutePath in selection }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (selection.isNotEmpty()) {
                    SelectionBar(
                        count = selection.size,
                        onClose = { viewModel.clearSelection() },
                        onSelectAll = { viewModel.selectAll() },
                        onCopy = { viewModel.copySelectedToClipboard() },
                        onCut = { viewModel.cutSelectedToClipboard() },
                        onRename = if (selected.size == 1) ({ dialog = SelectionDialog.RENAME }) else null,
                        onDelete = { dialog = SelectionDialog.DELETE }
                    )
                } else {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", onBack) }
                    )
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                StatusLine(busy)
                if (!busy && items.isEmpty()) EmptyMessage("No se encontró nada en esta categoría")
                FileList(
                    items = items,
                    selection = selection,
                    onOpen = { item -> openFileExternally(context, item.file)?.let { viewModel.message.value = it } },
                    onToggle = { viewModel.toggleSelection(it) }
                )
            }
        }
        SelectionDialogs(viewModel, selected, dialog) { dialog = null }
    }
}

@Composable
private fun SelectionDialogs(
    viewModel: FileViewModel,
    selected: List<FileItem>,
    dialog: SelectionDialog?,
    onClose: () -> Unit
) {
    when (dialog) {
        SelectionDialog.RENAME -> selected.singleOrNull()?.let { target ->
            NameInputDialog(
                title = "Renombrar",
                initialValue = target.name,
                onConfirm = {
                    viewModel.rename(target.file, it)
                    onClose()
                },
                onDismiss = onClose
            )
        }
        SelectionDialog.DELETE -> if (selected.isNotEmpty()) {
            ConfirmDeleteDialog(
                names = selected.map { it.name },
                onConfirm = {
                    viewModel.deleteSelected()
                    onClose()
                },
                onDismiss = onClose
            )
        }
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: () -> Unit
) {
    TopAppBar(
        title = { Text(if (count == 1) "1 seleccionado" else "$count seleccionados", maxLines = 1) },
        navigationIcon = { IconAction(Icons.Default.Close, "Cancelar selección", onClose) },
        actions = {
            IconAction(Icons.Default.SelectAll, "Seleccionar todo", onSelectAll)
            IconAction(Icons.Default.ContentCopy, "Copiar", onCopy)
            IconAction(Icons.Default.ContentCut, "Cortar", onCut)
            if (onRename != null) IconAction(Icons.Default.DriveFileRenameOutline, "Renombrar", onRename)
            IconAction(Icons.Default.Delete, "Eliminar", onDelete)
        }
    )
}

@Composable
private fun SortRow(current: SortMode, onSelect: (SortMode) -> Unit) {
    val options = listOf(
        SortMode.NAME to "Nombre",
        SortMode.DATE to "Fecha",
        SortMode.SIZE to "Tamaño",
        SortMode.TYPE to "Tipo"
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Ordenar:", style = MaterialTheme.typography.bodyMedium)
        options.forEach { (mode, label) ->
            val active = mode == current
            Text(
                text = label,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.plainClickable { onSelect(mode) }.padding(horizontal = 10.dp, vertical = 10.dp)
            )
        }
    }
}

// Texto fijo en vez de una barra de progreso indeterminada: esa barra se anima sin parar.
@Composable
private fun StatusLine(busy: Boolean) {
    if (busy) {
        Text(
            "Trabajando…",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FileList(
    items: List<FileItem>,
    selection: Set<String>,
    onOpen: (FileItem) -> Unit,
    onToggle: (FileItem) -> Unit
) {
    val selecting = selection.isNotEmpty()
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    LazyColumn {
        items(items, key = { it.file.absolutePath }) { item ->
            FileRow(
                item = item,
                selected = item.file.absolutePath in selection,
                dateFmt = dateFmt,
                onClick = { if (selecting) onToggle(item) else onOpen(item) },
                onLongClick = { onToggle(item) }
            )
            HorizontalDivider()
        }
    }
}

private fun iconFor(item: FileItem): ImageVector = when (item.kind()) {
    FileKind.FOLDER -> Icons.Default.Folder
    FileKind.IMAGE -> Icons.Default.Image
    FileKind.VIDEO -> Icons.Default.Movie
    FileKind.AUDIO -> Icons.Default.AudioFile
    FileKind.DOCUMENT -> Icons.Default.Description
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun FileRow(
    item: FileItem,
    selected: Boolean,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val highlight = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlight)
            .plainClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.CheckCircle else iconFor(item),
            contentDescription = if (selected) "Seleccionado" else null,
            tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = if (item.isDirectory) dateFmt.format(Date(item.lastModified))
                       else "${formatSize(item.sizeBytes)} · ${dateFmt.format(Date(item.lastModified))}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
