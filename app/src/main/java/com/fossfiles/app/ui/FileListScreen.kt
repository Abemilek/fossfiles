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
import androidx.compose.ui.res.stringResource
import com.fossfiles.app.R
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

    val selected = remember(items, selection) { if (selection.isEmpty()) emptyList<FileItem>() else items.filter { it.path in selection } }

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
                                IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) { if (!viewModel.goUp()) onBack() }
                            },
                            actions = {
                                IconAction(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.view_options)) { showOptions = !showOptions }
                                IconAction(Icons.Default.CreateNewFolder, stringResource(R.string.new_folder)) { showNewFolderDialog = true }
                            }
                        )
                        PlainTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearch(it) },
                            placeholder = stringResource(R.string.search_in_folder),
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
                    EmptyMessage(stringResource(if (searchQuery.isNotBlank() || filter.isActive) R.string.no_results else R.string.empty_folder))
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
                title = stringResource(R.string.new_folder),
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

    val selected = remember(items, selection) { if (selection.isEmpty()) emptyList<FileItem>() else items.filter { it.path in selection } }

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
                        navigationIcon = { IconAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), onBack) },
                        actions = {
                            IconAction(
                                if (grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
                                stringResource(if (grid) R.string.view_as_list else R.string.view_as_grid)
                            ) { viewModel.toggleGrid() }
                        }
                    )
                }
            }
        ) { padding ->
            Column(Modifier.padding(padding)) {
                StatusLine(viewModel.loadingVisible.value)
                if (!loading && items.isEmpty()) EmptyMessage(stringResource(R.string.category_empty))
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
                title = stringResource(R.string.rename),
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

    // Los textos se resuelven aquí (contexto composable) y no dentro de buildList.
    val sSelectAll = stringResource(R.string.select_all)
    val sInvert = stringResource(R.string.invert_selection)
    val sRemoveFav = stringResource(R.string.remove_favorite)
    val sAddFav = stringResource(R.string.add_favorite)
    val sRename = stringResource(R.string.rename)
    val sShare = stringResource(R.string.share)
    val sCompress = stringResource(R.string.compress)
    val sExtract = stringResource(R.string.extract)
    val sDetails = stringResource(R.string.details)
    val extra = buildList<Pair<String, () -> Unit>> {
        add(sSelectAll to { viewModel.selectAll() })
        add(sInvert to { viewModel.invertSelection() })
        val allFavorite = selected.isNotEmpty() && selected.all { it.path in favorites }
        add((if (allFavorite) sRemoveFav else sAddFav) to { viewModel.toggleFavoriteForSelection() })
        if (selected.size == 1) add(sRename to onRename)
        add(sShare to {
            shareFiles(context, selected.map { it.file })?.let { viewModel.message.value = it }
        })
        if (inBrowse) add(sCompress to { viewModel.compressSelected() })
        if (inBrowse && selected.any { !it.isDirectory && it.file.extension.equals("zip", ignoreCase = true) }) {
            add(sExtract to { viewModel.extractSelected() })
        }
        if (selected.size == 1) add(sDetails to { viewModel.showDetails(selected.first().file) })
    }

    Column {
        TopAppBar(
            title = { Text(pluralText(R.plurals.selected_count, count, count), maxLines = 1) },
            navigationIcon = { IconAction(Icons.Default.Close, stringResource(R.string.cancel_selection)) { viewModel.clearSelection() } },
            actions = {
                IconAction(Icons.Default.ContentCopy, stringResource(R.string.copy)) { viewModel.copySelectedToClipboard() }
                IconAction(Icons.Default.ContentCut, stringResource(R.string.cut)) { viewModel.cutSelectedToClipboard() }
                IconAction(Icons.Default.Delete, stringResource(R.string.delete), onDelete)
                IconAction(Icons.Default.MoreVert, stringResource(R.string.more_actions)) { showMore = !showMore }
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
        SortMode.NAME to stringResource(R.string.sort_name),
        SortMode.DATE to stringResource(R.string.sort_date),
        SortMode.SIZE to stringResource(R.string.sort_size),
        SortMode.TYPE to stringResource(R.string.sort_type)
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(R.string.sort_label), style = MaterialTheme.typography.bodyMedium)
        sorts.forEach { (mode, label) ->
            OptionText(label, active = mode == sort) { viewModel.setSort(mode) }
        }
        OptionText(stringResource(if (reverse) R.string.order_reversed else R.string.order_normal), active = reverse) { viewModel.toggleSortReverse() }
        Spacer(Modifier.width(12.dp))
        OptionText(stringResource(if (grid) R.string.view_mode_grid else R.string.view_mode_list), active = false) { viewModel.toggleGrid() }
        OptionText(stringResource(if (hidden) R.string.hidden_yes else R.string.hidden_no), active = hidden) { viewModel.toggleHidden() }
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
        Text(stringResource(R.string.filter_label), style = MaterialTheme.typography.bodyMedium)
        OptionText(stringResource(R.string.filter_type, typeFilterLabel(f.type)), active = f.type != TypeFilter.ALL) { viewModel.cycleTypeFilter() }
        OptionText(stringResource(R.string.filter_date, dateFilterLabel(f.date)), active = f.date != DateFilter.ANY) { viewModel.cycleDateFilter() }
        OptionText(stringResource(R.string.filter_size, sizeFilterLabel(f.size)), active = f.size != SizeFilter.ANY) { viewModel.cycleSizeFilter() }
        if (f.isActive) OptionText(stringResource(R.string.filter_clear), active = false) { viewModel.clearFilters() }
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
        DetailRow(stringResource(R.string.detail_location), file.parent ?: "/")
        DetailRow(
            stringResource(R.string.detail_type),
            if (details.isDirectory) stringResource(R.string.detail_folder) else (mimeTypeOf(file) ?: stringResource(R.string.detail_file))
        )
        DetailRow(
            stringResource(R.string.detail_size),
            if (!details.ready) stringResource(R.string.detail_calculating)
            else stringResource(R.string.detail_size_value, formatSize(details.sizeBytes), "%,d".format(details.sizeBytes))
        )
        if (details.isDirectory && details.ready) {
            DetailRow(
                stringResource(R.string.detail_contents),
                stringResource(
                    R.string.detail_contents_value,
                    pluralText(R.plurals.files_count, details.files, details.files),
                    pluralText(R.plurals.folders_count, details.folders, details.folders)
                )
            )
        }
        DetailRow(stringResource(R.string.detail_modified), dateFmt.format(Date(details.lastModified)))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PlainTextButton(stringResource(R.string.close), onDismiss)
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
            stringResource(R.string.loading),
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
    val what = pluralText(R.plurals.items_count, count, count)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(if (isCut) R.string.clipboard_to_move else R.string.clipboard_to_copy, what),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        PlainTextButton(stringResource(R.string.paste_here), onPaste)
        PlainTextButton(stringResource(R.string.cancel), onCancel)
    }
}

// Nombres de los filtros en el idioma activo (la lógica de filtrado no conoce idiomas).
@Composable
private fun typeFilterLabel(t: TypeFilter): String = stringResource(
    when (t) {
        TypeFilter.ALL -> R.string.type_all
        TypeFilter.FOLDERS -> R.string.type_folders
        TypeFilter.IMAGES -> R.string.type_images
        TypeFilter.VIDEOS -> R.string.type_videos
        TypeFilter.AUDIO -> R.string.type_audio
        TypeFilter.DOCUMENTS -> R.string.type_documents
    }
)

@Composable
private fun dateFilterLabel(d: DateFilter): String = stringResource(
    when (d) {
        DateFilter.ANY -> R.string.date_any
        DateFilter.DAY -> R.string.date_24h
        DateFilter.WEEK -> R.string.date_7d
        DateFilter.MONTH -> R.string.date_30d
        DateFilter.YEAR -> R.string.date_1y
    }
)

@Composable
private fun sizeFilterLabel(s: SizeFilter): String = stringResource(
    when (s) {
        SizeFilter.ANY -> R.string.size_any
        SizeFilter.OVER_1MB -> R.string.size_1mb
        SizeFilter.OVER_10MB -> R.string.size_10mb
        SizeFilter.OVER_100MB -> R.string.size_100mb
        SizeFilter.OVER_1GB -> R.string.size_1gb
    }
)
