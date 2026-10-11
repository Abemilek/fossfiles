package com.fossfiles.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fossfiles.app.FileItem
import com.fossfiles.app.FileViewModel
import com.fossfiles.app.SortMode
import com.fossfiles.app.formatSize
import com.fossfiles.app.openFileExternally
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileListScreen(viewModel: FileViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentDir by viewModel.currentDir
    val items by viewModel.items
    val searchQuery by viewModel.searchQuery
    val clipboardItem by viewModel.clipboardItem
    val isLoading by viewModel.isLoading

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var itemMenuTarget by remember { mutableStateOf<FileItem?>(null) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(currentDir.name.ifBlank { "/" }, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { if (!viewModel.goUp()) onBack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Ordenar")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            listOf(
                                SortMode.NAME to "Nombre",
                                SortMode.DATE to "Fecha",
                                SortMode.SIZE to "Tamaño",
                                SortMode.TYPE to "Tipo"
                            ).forEach { (mode, label) ->
                                DropdownMenuItem(text = { Text(label) }, onClick = {
                                    viewModel.setSort(mode)
                                    showSortMenu = false
                                })
                            }
                        }
                        if (clipboardItem != null) {
                            IconButton(onClick = { viewModel.pasteFromClipboard() }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Pegar")
                            }
                        }
                    }
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearch(it) },
                    placeholder = { Text("Buscar en esta carpeta...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewFolderDialog = true }) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "Nueva carpeta")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!isLoading && items.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Carpeta vacía", style = MaterialTheme.typography.bodyMedium)
                }
            }
            LazyColumn {
                items(items, key = { it.file.absolutePath }) { item ->
                    FileRow(
                        item = item,
                        onClick = {
                            if (item.isDirectory) viewModel.openDirectory(item.file)
                            else openFileExternally(context, item.file)
                        },
                        onLongClick = { itemMenuTarget = item }
                    )
                    Divider()
                }
            }
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

    renameTarget?.let { target ->
        NameInputDialog(
            title = "Renombrar",
            initialValue = target.name,
            onConfirm = {
                viewModel.rename(target.file, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    itemMenuTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { itemMenuTarget = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    target.name,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                ActionRow(Icons.Default.DriveFileRenameOutline, "Renombrar") {
                    renameTarget = target
                    itemMenuTarget = null
                }
                ActionRow(Icons.Default.ContentCopy, "Copiar") {
                    viewModel.copyToClipboard(target.file)
                    itemMenuTarget = null
                }
                ActionRow(Icons.Default.ContentCut, "Cortar") {
                    viewModel.cutToClipboard(target.file)
                    itemMenuTarget = null
                }
                ActionRow(Icons.Default.Delete, "Eliminar") {
                    viewModel.delete(target.file)
                    itemMenuTarget = null
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Text(label)
    }
}

/**
 * Vista plana para las categorías del Home (Fotos, Videos, Audio, Descargas,
 * Documentos): no navega a subcarpetas, solo lista los archivos ya escaneados.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CategoryListScreen(viewModel: FileViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val items by viewModel.items
    val isLoading by viewModel.isLoading
    val title by viewModel.categoryTitle
    var itemMenuTarget by remember { mutableStateOf<FileItem?>(null) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!isLoading && items.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No se encontró nada en esta categoría", style = MaterialTheme.typography.bodyMedium)
                }
            }
            LazyColumn {
                items(items, key = { it.file.absolutePath }) { item ->
                    FileRow(item = item, onClick = { openFileExternally(context, item.file) }, onLongClick = { itemMenuTarget = item })
                    Divider()
                }
            }
        }
    }

    renameTarget?.let { target ->
        NameInputDialog(
            title = "Renombrar",
            initialValue = target.name,
            onConfirm = {
                viewModel.rename(target.file, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    itemMenuTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { itemMenuTarget = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(target.name, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                ActionRow(Icons.Default.DriveFileRenameOutline, "Renombrar") {
                    renameTarget = target
                    itemMenuTarget = null
                }
                ActionRow(Icons.Default.Delete, "Eliminar") {
                    viewModel.delete(target.file)
                    itemMenuTarget = null
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileRow(item: FileItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1)
            Text(
                text = if (item.isDirectory) dateFmt.format(Date(item.lastModified))
                       else "${formatSize(item.sizeBytes)} · ${dateFmt.format(Date(item.lastModified))}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun NameInputDialog(
    title: String,
    initialValue: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text) }) { Text("Aceptar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
