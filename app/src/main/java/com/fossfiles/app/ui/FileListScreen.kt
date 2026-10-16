package com.fossfiles.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fossfiles.app.DateFilter
import com.fossfiles.app.FileDetails
import com.fossfiles.app.FileItem
import com.fossfiles.app.FileViewModel
import com.fossfiles.app.SizeFilter
import com.fossfiles.app.SortMode
import com.fossfiles.app.TypeFilter
import com.fossfiles.app.formatSize
import com.fossfiles.app.mimeTypeOf
import com.fossfiles.app.openFileExternally
import com.fossfiles.app.shareFiles
import java.util.Date

private enum class SelectionDialog { RENAME, DELETE }

/** Navegador de carpetas: buscar, ordenar, ver como lista o cuadrícula, ocultos, crear carpeta y pegar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(viewModel: FileViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentDir by viewModel.currentDir
    val items by viewModel.items
    val searchQuery by viewModel.searchQuery
    val clipboard by viewModel.clipboard
    val selection by viewModel.selection
    val grid by viewModel.gridView
    val details by viewModel.details
    val favorites by viewModel.favorites
    val filter by viewModel.filter
    val loadedDir by viewModel.loadedDir
    val trashDays by viewModel.trashDays
    val loading = viewModel.isLoading.value
    // Mientras la lista aún es la de la carpeta anterior no se aceptan toques (podrían abrir algo equivocado).
    val upToDate = loadedDir == currentDir

    // Sobreviven a girar la pantalla.
    var showNewFolderDialog by rememberSaveable { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<SelectionDialog?>(null) }

    val selected = items.filter { it.path in selection }

    // Al volver a una carpeta se recupera por dónde iba la lista; al entrar en una nueva, empieza arriba.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    val gridNow by rememberUpdatedState(grid)
    DisposableEffect(loadedDir) {
        val dir = loadedDir
        onDispose {
            if (dir != null) {
                if (gridNow) {
                    viewModel.rememberScroll(dir, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
                } else {
                    viewModel.rememberScroll(dir, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
                }
            }
        }
    }
    LaunchedEffect(loadedDir) {
        val dir = loadedDir ?: return@LaunchedEffect
        val saved = viewModel.scrollFor(dir)
        val index = saved?.get(0) ?: 0
        val offset = saved?.get(1) ?: 0
        listState.scrollToItem(index, offset)
        gridState.scrollToItem(index, offset)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (selection.isNotEmpty()) {
                    SelectionTopBar(
                        count = selection.size,
                        selected = selected,
                        favorites = favorites,
                        inBrowse = true,
                        viewModel = viewModel,
                        onRename = { dialog = SelectionDialog.RENAME },
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
                                IconAction(Icons.AutoMirrored.Filled.Sort, "Opciones de vista") { showOptions = !showOptions }
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
                        if (showOptions) OptionsRow(viewModel)
                        // Con filtros activos la fila queda visible para que se note que hay una búsqueda en curso.
                        if (showOptions || filter.isActive) FilterRow(viewModel)
                    }
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                if (clipboard.isNotEmpty()) {
                    ClipboardStrip(
                        count = clipboard.size,
                        isCut = viewModel.clipboardIsCut.value,
                        onPaste = { viewModel.pasteHere() },
                        onCancel = { viewModel.clearClipboard() }
                    )
                }
                StatusLine(viewModel.loadingVisible.value)
                if (!loading && upToDate && items.isEmpty()) {
                    EmptyMessage(if (searchQuery.isNotBlank() || filter.isActive) "Sin resultados" else "Carpeta vacía")
                }
                FileCollection(
                    items = items,
                    selection = selection,
                    favorites = favorites,
                    grid = grid,
                    onOpen = { item -> openItem(viewModel, context, item, items) },
                    onToggle = { viewModel.toggleSelection(it) },
                    onLongPress = { viewModel.longPress(it) },
                    interactive = upToDate,
                    listState = listState,
                    gridState = gridState
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
        SelectionDialogs(viewModel, selected, trashDays, dialog) { dialog = null }
        details?.let { DetailsDialog(it) { viewModel.closeDetails() } }
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
    val grid by viewModel.gridView
    val details by viewModel.details
    val favorites by viewModel.favorites
    val trashDays by viewModel.trashDays
    val loading = viewModel.isLoading.value
    var dialog by rememberSaveable { mutableStateOf<SelectionDialog?>(null) }

    val selected = items.filter { it.path in selection }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (selection.isNotEmpty()) {
                    SelectionTopBar(
                        count = selection.size,
                        selected = selected,
                        favorites = favorites,
                        inBrowse = false,
                        viewModel = viewModel,
                        onRename = { dialog = SelectionDialog.RENAME },
                        onDelete = { dialog = SelectionDialog.DELETE }
                    )
                } else {
                    TopAppBar(
                        title = { Text(title) },
                        navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Atrás", onBack) },
                        actions = {
                            IconAction(
                                if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                if (grid) "Ver como lista" else "Ver como cuadrícula"
                            ) { viewModel.toggleGrid() }
                        }
                    )
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                StatusLine(viewModel.loadingVisible.value)
                if (!loading && items.isEmpty()) EmptyMessage("No se encontró nada en esta categoría")
                FileCollection(
                    items = items,
                    selection = selection,
                    favorites = favorites,
                    grid = grid,
                    onOpen = { item -> openItem(viewModel, context, item, items) },
                    onToggle = { viewModel.toggleSelection(it) },
                    onLongPress = { viewModel.longPress(it) }
                )
            }
        }
        SelectionDialogs(viewModel, selected, trashDays, dialog) { dialog = null }
        details?.let { DetailsDialog(it) { viewModel.closeDetails() } }
    }
}

@Composable
private fun SelectionDialogs(
    viewModel: FileViewModel,
    selected: List<FileItem>,
    trashDays: Int,
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
                onDismiss = onClose,
                selectBaseName = !target.isDirectory
            )
        }
        SelectionDialog.DELETE -> if (selected.isNotEmpty()) {
            DeleteChoiceDialog(
                names = selected.map { it.name },
                trashDays = trashDays,
                onTrash = {
                    viewModel.deleteSelected(permanent = false)
                    onClose()
                },
                onPermanent = {
                    viewModel.deleteSelected(permanent = true)
                    onClose()
                },
                onDismiss = onClose
            )
        }
        null -> Unit
    }
}

/**
 * Barra superior en modo selección. Las acciones frecuentes (copiar, cortar, eliminar) van
 * como íconos; el resto sale en una segunda fila de texto (sin menú desplegable animado).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    selected: List<FileItem>,
    favorites: Set<String>,
    inBrowse: Boolean,
    viewModel: FileViewModel,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    // Se reinicia (a cerrada) cada vez que la selección se vacía y vuelve a empezar.
    var showMore by remember(count > 0) { mutableStateOf(false) }

    val extra = buildList<Pair<String, () -> Unit>> {
        add("Seleccionar todo" to { viewModel.selectAll() })
        add("Invertir selección" to { viewModel.invertSelection() })
        val allFavorite = selected.isNotEmpty() && selected.all { it.path in favorites }
        add((if (allFavorite) "Quitar de favoritos" else "Añadir a favoritos") to { viewModel.toggleFavoriteForSelection() })
        if (selected.size == 1) add("Renombrar" to onRename)
        add("Compartir" to {
            shareFiles(context, selected.map { it.file })?.let { viewModel.message.value = it }
        })
        if (inBrowse) add("Comprimir" to { viewModel.compressSelected() })
        if (inBrowse && selected.any { !it.isDirectory && it.file.extension.equals("zip", ignoreCase = true) }) {
            add("Extraer" to { viewModel.extractSelected() })
        }
        if (selected.size == 1) add("Detalles" to { viewModel.showDetails(selected.first().file) })
    }

    Column {
        TopAppBar(
            title = { Text(if (count == 1) "1 seleccionado" else "$count seleccionados", maxLines = 1) },
            navigationIcon = { IconAction(Icons.Default.Close, "Cancelar selección") { viewModel.clearSelection() } },
            actions = {
                IconAction(Icons.Default.ContentCopy, "Copiar") { viewModel.copySelectedToClipboard() }
                IconAction(Icons.Default.ContentCut, "Cortar") { viewModel.cutSelectedToClipboard() }
                IconAction(Icons.Default.Delete, "Eliminar", onDelete)
                IconAction(Icons.Default.MoreVert, "Más acciones") { showMore = !showMore }
            }
        )
        if (showMore) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                extra.forEach { (label, action) ->
                    PlainTextButton(label) {
                        action()
                        showMore = false
                    }
                }
            }
        }
    }
}

/** Ordenar, tipo de vista y archivos ocultos, en una fila que se desplaza de lado. */
@Composable
private fun OptionsRow(viewModel: FileViewModel) {
    val sort by viewModel.sortMode
    val grid by viewModel.gridView
    val hidden by viewModel.showHidden
    val reverse by viewModel.sortReverse
    val sorts = listOf(
        SortMode.NAME to "Nombre",
        SortMode.DATE to "Fecha",
        SortMode.SIZE to "Tamaño",
        SortMode.TYPE to "Tipo"
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Ordenar:", style = MaterialTheme.typography.bodyMedium)
        sorts.forEach { (mode, label) ->
            OptionText(label, active = mode == sort) { viewModel.setSort(mode) }
        }
        OptionText(if (reverse) "Orden: inverso" else "Orden: normal", active = reverse) { viewModel.toggleSortReverse() }
        Spacer(Modifier.width(12.dp))
        OptionText(if (grid) "Vista: cuadrícula" else "Vista: lista", active = false) { viewModel.toggleGrid() }
        OptionText(if (hidden) "Ocultos: sí" else "Ocultos: no", active = hidden) { viewModel.toggleHidden() }
    }
}

/** Filtros por tipo, fecha y tamaño: cada toque pasa a la siguiente opción. Buscan también en subcarpetas. */
@Composable
private fun FilterRow(viewModel: FileViewModel) {
    val f by viewModel.filter
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Filtrar:", style = MaterialTheme.typography.bodyMedium)
        OptionText("Tipo: ${f.type.label}", active = f.type != TypeFilter.ALL) { viewModel.cycleTypeFilter() }
        OptionText("Fecha: ${f.date.label}", active = f.date != DateFilter.ANY) { viewModel.cycleDateFilter() }
        OptionText("Tamaño: ${f.size.label}", active = f.size != SizeFilter.ANY) { viewModel.cycleSizeFilter() }
        if (f.isActive) OptionText("Limpiar", active = false) { viewModel.clearFilters() }
    }
}

@Composable
private fun OptionText(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        maxLines = 1,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.plainClickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp)
    )
}

@Composable
private fun DetailsDialog(details: FileDetails, onDismiss: () -> Unit) {
    val dateFmt = rememberDateFormat()
    val file = details.file
    ModalOverlay(onDismiss = onDismiss) {
        Text(file.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        DetailRow("Ubicación", file.parent ?: "/")
        DetailRow("Tipo", if (details.isDirectory) "Carpeta" else (mimeTypeOf(file) ?: "Archivo"))
        DetailRow(
            "Tamaño",
            if (!details.ready) "Calculando…"
            else "${formatSize(details.sizeBytes)} (${"%,d".format(details.sizeBytes)} bytes)"
        )
        if (details.isDirectory && details.ready) {
            DetailRow("Contenido", "${details.files} archivos, ${details.folders} carpetas")
        }
        DetailRow("Modificado", dateFmt.format(Date(details.lastModified)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PlainTextButton("Cerrar", onDismiss)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

/** Avisa de que la carpeta tarda en cargar. Texto fijo: una barra indeterminada se anima sin parar. */
@Composable
internal fun StatusLine(show: Boolean) {
    if (show) {
        Text(
            "Cargando…",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        )
    }
}

@Composable
internal fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Lo que hay en el portapapeles (copiar / cortar) y cómo usarlo: pegar en la carpeta actual o descartar. */
@Composable
private fun ClipboardStrip(count: Int, isCut: Boolean, onPaste: () -> Unit, onCancel: () -> Unit) {
    val what = if (count == 1) "1 elemento" else "$count elementos"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isCut) "$what para mover" else "$what para copiar",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        PlainTextButton("Pegar aquí", onPaste)
        PlainTextButton("Cancelar", onCancel)
    }
}
