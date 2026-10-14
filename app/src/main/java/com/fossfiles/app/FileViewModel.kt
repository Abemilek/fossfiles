package com.fossfiles.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ScreenMode { HOME, BROWSE, CATEGORY, TRASH }
enum class Category(val label: String) {
    IMAGES("Fotos"), VIDEOS("Videos"), AUDIO("Audio"), DOWNLOADS("Descargas"), DOCUMENTS("Documentos"), FAVORITES("Favoritos")
}

/** Imágenes que se pueden recorrer en el visor y cuál se está viendo. */
data class ViewerState(val images: List<FileItem>, val index: Int)

/** Lista de audio/video del reproductor y cuál suena. */
data class MediaState(val items: List<FileItem>, val index: Int)

/** Acceso rápido a una carpeta conocida del almacenamiento. */
data class QuickLink(val label: String, val dir: File)

/** Datos de la ventana de detalles. En carpetas, [ready] pasa a true cuando termina de medir. */
data class FileDetails(
    val file: File,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val files: Int,
    val folders: Int,
    val lastModified: Long,
    val ready: Boolean
)

private class Loaded(val items: List<FileItem>, val notice: String? = null)

// appContext: SIEMPRE el applicationContext, nunca el de la Activity — así el
// ViewModel jamás retiene una Activity y no hay riesgo de fuga en rotaciones.
class FileViewModel(rootDir: File, private val appContext: Context) : ViewModel() {

    private val root = rootDir

    // Papelera local: oculta (empieza con punto) y con .nomedia, así no aparece en Fotos ni en la galería.
    private val trash = TrashBin(File(rootDir, ".fossfiles-trash"))

    // Preferencias solo locales (SharedPreferences privado de la app): vista, ocultos y orden.
    private val prefs = appContext.getSharedPreferences("fossfiles", Context.MODE_PRIVATE)

    // Solo hay una carga (listado, búsqueda o categoría) viva a la vez: al lanzar otra,
    // la anterior se cancela de verdad (incluido el recorrido de disco), así una búsqueda
    // vieja nunca puede pisar los resultados de una nueva.
    private var loadJob: Job? = null
    private var detailsJob: Job? = null
    private var anchorPath: String? = null // último elemento marcado: punto de partida de la selección por rango

    val screenMode = mutableStateOf(ScreenMode.HOME)
    val isLoading = mutableStateOf(false)
    val isWorking = mutableStateOf(false) // copiando / moviendo / borrando / comprimiendo
    val storageInfo = mutableStateOf(StorageInfo(0, 0))

    val quickLinks = mutableStateOf<List<QuickLink>>(emptyList())
    val recent = mutableStateOf<List<FileItem>>(emptyList())

    val currentDir = mutableStateOf(rootDir)
    val items = mutableStateOf<List<FileItem>>(emptyList())
    val sortMode = mutableStateOf(
        try { SortMode.valueOf(prefs.getString("sort", null) ?: "NAME") } catch (e: IllegalArgumentException) { SortMode.NAME }
    )
    val gridView = mutableStateOf(prefs.getBoolean("grid", false))
    val showHidden = mutableStateOf(prefs.getBoolean("hidden", false))
    val searchQuery = mutableStateOf("")

    val currentCategory = mutableStateOf<Category?>(null)
    val categoryTitle = mutableStateOf("")

    /** Rutas absolutas de los elementos seleccionados. */
    val selection = mutableStateOf<Set<String>>(emptySet())

    val clipboard = mutableStateOf<List<File>>(emptyList())
    val clipboardIsCut = mutableStateOf(false)

    val details = mutableStateOf<FileDetails?>(null)
    val viewer = mutableStateOf<ViewerState?>(null)

    val media = mutableStateOf<MediaState?>(null)
    // Al terminar una pista pasa sola a la siguiente de la lista (si hay).
    val player = PlayerController(appContext) { advanceAfterCompletion() }

    val filter = mutableStateOf(SearchFilter())

    /** Rutas absolutas de los favoritos (guardadas solo en el almacenamiento privado de la app). */
    val favorites = mutableStateOf<Set<String>>(HashSet(prefs.getStringSet("fav", emptySet()) ?: emptySet()))

    val trashEntries = mutableStateOf<List<TrashBin.Entry>>(emptyList())
    val trashSelection = mutableStateOf<Set<String>>(emptySet()) // ids

    /** Mensaje de error/aviso para mostrar una vez; la UI lo limpia con [dismissMessage]. */
    val message = mutableStateOf<String?>(null)

    init {
        refreshHome()
    }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { StorageStats.read(root) }
            storageInfo.value = info
        }
    }

    /** Actualiza lo que muestra el Home: almacenamiento, accesos rápidos y recientes. */
    fun refreshHome() {
        loadStorageInfo()
        viewModelScope.launch {
            val (links, latest) = withContext(Dispatchers.IO) {
                val known = listOf(
                    "Descargas" to "Download",
                    "Cámara" to "DCIM",
                    "Imágenes" to "Pictures",
                    "Documentos" to "Documents",
                    "Música" to "Music",
                    "Películas" to "Movies"
                )
                val existing = known.map { QuickLink(it.first, File(root, it.second)) }.filter { it.dir.isDirectory }
                // Aprovecha para vaciar de la papelera lo que lleva más de 30 días.
                try { trash.purgeOlderThan(TRASH_DAYS * DAY_MS) } catch (e: Exception) { /* no es crítico */ }
                existing to MediaStoreScanner.recent(appContext)
            }
            quickLinks.value = links
            recent.value = latest
        }
    }

    fun dismissMessage() { message.value = null }

    // ---------- Carga ----------

    private fun launchLoad(debounceMs: Long = 0L, work: (isActive: () -> Boolean) -> Loaded) {
        loadJob?.cancel()
        isLoading.value = true
        val job = viewModelScope.launch {
            try {
                if (debounceMs > 0) delay(debounceMs)
                val loaded = withContext(Dispatchers.IO) { work { isActive } }
                items.value = loaded.items
                // Si algo de lo seleccionado ya no está en la lista, se quita de la selección.
                if (selection.value.isNotEmpty()) {
                    val present = HashSet<String>()
                    loaded.items.forEach { present.add(it.file.absolutePath) }
                    selection.value = selection.value.filterTo(HashSet()) { it in present }
                }
                loaded.notice?.let { message.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                items.value = emptyList()
                message.value = e.message ?: "No se pudo leer esta ubicación"
            }
        }
        loadJob = job
        job.invokeOnCompletion { if (loadJob === job) isLoading.value = false }
    }

    private fun loadDirectory(debounceMs: Long = 0L) {
        val dir = currentDir.value
        val query = searchQuery.value.trim()
        val mode = sortMode.value
        val hidden = showHidden.value
        val activeFilter = filter.value
        launchLoad(debounceMs) { active ->
            if (query.isEmpty() && !activeFilter.isActive) {
                Loaded(FileRepository.listDirectory(dir, mode, hidden))
            } else {
                // Con texto o con filtros activos se busca también en las subcarpetas.
                val r = FileRepository.search(dir, query, mode, active, hidden, activeFilter)
                Loaded(
                    r.items,
                    if (r.truncated) "Mostrando solo los primeros ${FileRepository.MAX_SEARCH_RESULTS} resultados" else null
                )
            }
        }
    }

    private fun loadCategory(category: Category) {
        val downloads = File(root, "Download")
        val hidden = showHidden.value
        launchLoad { active ->
            Loaded(
                when (category) {
                    Category.IMAGES -> MediaStoreScanner.images(appContext)
                    Category.VIDEOS -> MediaStoreScanner.videos(appContext)
                    Category.AUDIO -> MediaStoreScanner.audio(appContext)
                    Category.DOWNLOADS ->
                        // En versiones viejas de Android MediaStore.Downloads no existe:
                        // se cae a leer la carpeta directamente.
                        MediaStoreScanner.downloads(appContext).ifEmpty {
                            FileRepository.scanCategory(listOf(downloads), null, 1, active, hidden)
                        }
                    Category.DOCUMENTS -> FileRepository.scanCategory(
                        listOf(File(root, "Documents"), downloads),
                        DOCUMENT_EXTENSIONS,
                        2,
                        active,
                        hidden
                    )
                    Category.FAVORITES -> favoriteItems()
                }
            )
        }
    }

    /** Favoritos que todavía existen; los que ya no, se quitan de la lista guardada. */
    private fun favoriteItems(): List<FileItem> {
        val existing = favorites.value.map { File(it) }.filter { it.exists() }
        if (existing.size != favorites.value.size) {
            val keep = existing.mapTo(HashSet()) { it.absolutePath }
            updateFavorites { it.filterTo(HashSet()) { path -> path in keep } }
        }
        return FileRepository.sortItems(existing.map { FileItem(it) }, SortMode.NAME)
    }

    /** Vuelve a cargar lo que se esté viendo (carpeta, búsqueda, categoría o papelera). */
    fun reload() {
        when (screenMode.value) {
            ScreenMode.BROWSE -> loadDirectory()
            ScreenMode.CATEGORY -> currentCategory.value?.let { loadCategory(it) }
            ScreenMode.HOME -> refreshHome()
            ScreenMode.TRASH -> loadTrash()
        }
    }

    // ---------- Navegación ----------

    fun goHome() {
        loadJob?.cancel()
        isLoading.value = false
        clearSelection()
        trashSelection.value = emptySet()
        screenMode.value = ScreenMode.HOME
        searchQuery.value = ""
        filter.value = SearchFilter()
        refreshHome()
    }

    fun openInternalStorage() {
        screenMode.value = ScreenMode.BROWSE
        openDirectory(root)
    }

    fun openQuickLink(link: QuickLink) = openFolder(link.dir)

    /** Abre una carpeta en el explorador desde cualquier pantalla (Home, favoritos, accesos rápidos). */
    fun openFolder(dir: File) {
        screenMode.value = ScreenMode.BROWSE
        openDirectory(dir)
    }

    fun openCategory(category: Category) {
        clearSelection()
        currentCategory.value = category
        categoryTitle.value = category.label
        screenMode.value = ScreenMode.CATEGORY
        loadCategory(category)
    }

    fun openDirectory(dir: File) {
        clearSelection()
        currentDir.value = dir
        searchQuery.value = ""
        filter.value = SearchFilter()
        loadDirectory()
    }

    /** true si subió de nivel, false si ya estaba en la raíz (para volver al Home) */
    fun goUp(): Boolean {
        val parent = currentDir.value.parentFile
        return if (parent != null && currentDir.value != root) {
            openDirectory(parent)
            true
        } else {
            false
        }
    }

    // ---------- Preferencias de vista ----------

    fun setSort(mode: SortMode) {
        sortMode.value = mode
        prefs.edit().putString("sort", mode.name).apply()
        loadDirectory()
    }

    fun toggleGrid() {
        gridView.value = !gridView.value
        prefs.edit().putBoolean("grid", gridView.value).apply()
    }

    fun toggleHidden() {
        showHidden.value = !showHidden.value
        prefs.edit().putBoolean("hidden", showHidden.value).apply()
        reload()
    }

    fun setSearch(query: String) {
        searchQuery.value = query
        clearSelection()
        // Espera un poco a que el usuario deje de escribir antes de buscar. Lanzar otra
        // carga cancela la anterior, incluso si ya estaba recorriendo el disco.
        loadDirectory(debounceMs = if (query.isBlank()) 0L else 300L)
    }

    // ---------- Filtros de búsqueda ----------

    private fun <T : Enum<T>> next(values: Array<T>, current: T): T = values[(current.ordinal + 1) % values.size]

    private fun applyFilter(newFilter: SearchFilter) {
        filter.value = newFilter
        clearSelection()
        loadDirectory()
    }

    // Cada toque pasa a la siguiente opción (sin menús desplegables animados).
    fun cycleTypeFilter() = applyFilter(filter.value.copy(type = next(TypeFilter.values(), filter.value.type)))
    fun cycleDateFilter() = applyFilter(filter.value.copy(date = next(DateFilter.values(), filter.value.date)))
    fun cycleSizeFilter() = applyFilter(filter.value.copy(size = next(SizeFilter.values(), filter.value.size)))
    fun clearFilters() = applyFilter(SearchFilter())

    // ---------- Selección ----------

    fun toggleSelection(item: FileItem) {
        val path = item.file.absolutePath
        if (path in selection.value) {
            selection.value = selection.value - path
        } else {
            selection.value = selection.value + path
            anchorPath = path
        }
    }

    /** Mantener pulsado: sin selección empieza una; con selección marca todo el rango desde el último marcado. */
    fun longPress(item: FileItem) {
        if (selection.value.isEmpty()) toggleSelection(item) else selectRangeTo(item)
    }

    private fun selectRangeTo(item: FileItem) {
        val list = items.value
        val path = item.file.absolutePath
        val to = list.indexOfFirst { it.file.absolutePath == path }
        if (to < 0) return
        val anchor = anchorPath
        val from = if (anchor == null) -1 else list.indexOfFirst { it.file.absolutePath == anchor }
        if (from < 0) {
            toggleSelection(item)
            return
        }
        val range = if (from <= to) from..to else to..from
        selection.value = selection.value + range.map { list[it].file.absolutePath }
        anchorPath = path
    }

    /** Marca todos los elementos que no estaban seleccionados y desmarca los que sí. */
    fun invertSelection() {
        val all = items.value.mapTo(HashSet()) { it.file.absolutePath }
        selection.value = all - selection.value
    }

    fun selectAll() {
        selection.value = items.value.mapTo(HashSet()) { it.file.absolutePath }
    }

    fun clearSelection() {
        anchorPath = null
        if (selection.value.isNotEmpty()) selection.value = emptySet()
    }

    private fun selectedFiles(): List<File> =
        items.value.filter { it.file.absolutePath in selection.value }.map { it.file }

    // ---------- Operaciones ----------

    /** Ejecuta [op] para cada elemento en segundo plano, junta los errores y al final llama a [onDone]. */
    private fun <T> runTasks(items: List<T>, label: (T) -> String, op: (T) -> Unit, onDone: () -> Unit) {
        if (items.isEmpty() || isWorking.value) return
        isWorking.value = true
        viewModelScope.launch {
            val errors = withContext(Dispatchers.IO) {
                items.mapNotNull { item ->
                    try {
                        op(item)
                        null
                    } catch (e: FileOpException) {
                        e.message
                    } catch (e: Exception) {
                        "Error inesperado con «${label(item)}»"
                    }
                }
            }
            isWorking.value = false
            if (errors.isNotEmpty()) {
                message.value = errors.first() +
                    if (errors.size > 1) " (y ${errors.size - 1} errores más)" else ""
            }
            onDone()
        }
    }

    private fun runBatch(files: List<File>, op: (File) -> Unit) =
        runTasks(files, { it.name }, op) { reload() }

    fun createFolder(name: String) {
        val parent = currentDir.value
        runBatch(listOf(parent)) { FileRepository.createFolder(it, name) }
    }

    fun rename(item: File, newName: String) {
        clearSelection()
        runBatch(listOf(item)) {
            val renamed = FileRepository.rename(it, newName)
            if (it.absolutePath in favorites.value) {
                updateFavorites { favs -> favs - it.absolutePath + renamed.absolutePath }
            }
        }
    }

    /** Envía a la papelera (se puede restaurar) o, con [permanent], borra para siempre. */
    fun deleteSelected(permanent: Boolean) {
        val files = selectedFiles()
        clearSelection()
        runBatch(files) {
            if (permanent) FileRepository.delete(it) else trash.moveToTrash(it)
            if (it.absolutePath in favorites.value) updateFavorites { favs -> favs - it.absolutePath }
        }
    }

    fun copySelectedToClipboard() {
        clipboard.value = selectedFiles()
        clipboardIsCut.value = false
        clearSelection()
    }

    fun cutSelectedToClipboard() {
        clipboard.value = selectedFiles()
        clipboardIsCut.value = true
        clearSelection()
    }

    fun pasteHere() {
        val sources = clipboard.value
        if (sources.isEmpty() || screenMode.value != ScreenMode.BROWSE) return
        val dest = currentDir.value
        val cut = clipboardIsCut.value
        clipboard.value = emptyList()
        runBatch(sources) { if (cut) FileRepository.move(it, dest) else FileRepository.copy(it, dest) }
    }

    /** Comprime lo seleccionado en un .zip dentro de la carpeta actual. */
    fun compressSelected() {
        if (screenMode.value != ScreenMode.BROWSE) return
        val files = selectedFiles()
        val dest = currentDir.value
        clearSelection()
        runBatch(listOf(dest)) { FileRepository.zip(files, it) }
    }

    /** Extrae cada ZIP seleccionado en una carpeta nueva junto a él. */
    fun extractSelected() {
        val zips = selectedFiles().filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
        clearSelection()
        runBatch(zips) { FileRepository.unzip(it) }
    }

    // ---------- Detalles ----------

    fun showDetails(file: File) {
        detailsJob?.cancel()
        val isDir = file.isDirectory
        details.value = FileDetails(
            file = file,
            isDirectory = isDir,
            sizeBytes = if (isDir) 0L else file.length(),
            files = 0,
            folders = 0,
            lastModified = file.lastModified(),
            ready = !isDir
        )
        if (!isDir) return
        // Medir una carpeta grande puede tardar: se hace aparte y se cancela si se cierra la ventana.
        detailsJob = viewModelScope.launch {
            val stats = withContext(Dispatchers.IO) { FileRepository.measure(file) { isActive } }
            details.value = details.value?.takeIf { it.file == file }
                ?.copy(sizeBytes = stats.bytes, files = stats.files, folders = stats.folders, ready = true)
        }
    }

    fun closeDetails() {
        detailsJob?.cancel()
        details.value = null
    }

    // ---------- Visor de imágenes ----------

    /** Las imágenes que el visor sabe mostrar (el SVG se abre con otra app). */
    fun canPreview(item: FileItem): Boolean =
        item.kind() == FileKind.IMAGE && !item.file.extension.equals("svg", ignoreCase = true)

    /** Abre el visor en [item] y deja recorrer las demás imágenes de [source]. */
    fun openViewer(item: FileItem, source: List<FileItem>) {
        val images = source.filter { canPreview(it) }
        val index = images.indexOfFirst { it.file == item.file }
        if (index >= 0) viewer.value = ViewerState(images, index)
    }

    fun viewerStep(delta: Int) {
        val state = viewer.value ?: return
        val next = state.index + delta
        if (next in state.images.indices) viewer.value = state.copy(index = next)
    }

    fun closeViewer() { viewer.value = null }

    // ---------- Reproductor de audio y video ----------

    /** ¿Se puede reproducir dentro de la app? (Solo audio y video; el resto se abre con otra app.) */
    fun canPlay(item: FileItem): Boolean = item.kind() == FileKind.AUDIO || item.kind() == FileKind.VIDEO

    /** Abre el reproductor en [item] y deja recorrer los demás audios/videos de [source]. */
    fun openMedia(item: FileItem, source: List<FileItem>) {
        val playlist = source.filter { canPlay(it) }
        val index = playlist.indexOfFirst { it.file == item.file }
        if (index < 0) return
        media.value = MediaState(playlist, index)
        player.load(playlist[index].file)
    }

    fun mediaStep(delta: Int) {
        val state = media.value ?: return
        val next = state.index + delta
        if (next in state.items.indices) {
            media.value = state.copy(index = next)
            player.load(state.items[next].file)
        }
    }

    private fun advanceAfterCompletion() {
        val state = media.value ?: return
        if (state.index + 1 in state.items.indices) mediaStep(1)
    }

    fun closeMedia() {
        player.release()
        media.value = null
    }

    /** Al salir de la app el audio/video se pausa (no hay servicio en segundo plano). */
    fun pausePlayback() = player.pause()

    override fun onCleared() {
        player.release()
    }

    // ---------- Favoritos ----------

    /** Aplica [change] al conjunto de favoritos y lo persiste (sincronizado por si cambia en paralelo). */
    @Synchronized
    private fun updateFavorites(change: (Set<String>) -> Set<String>) {
        val updated = change(favorites.value)
        favorites.value = updated
        prefs.edit().putStringSet("fav", HashSet(updated)).apply()
    }

    /** Si todo lo seleccionado ya es favorito lo quita; si no, lo añade. */
    fun toggleFavoriteForSelection() {
        val paths = selectedFiles().map { it.absolutePath }
        if (paths.isEmpty()) return
        val allFavorite = paths.all { it in favorites.value }
        updateFavorites { if (allFavorite) it - paths.toSet() else it + paths }
        clearSelection()
        if (screenMode.value == ScreenMode.CATEGORY && currentCategory.value == Category.FAVORITES) reload()
    }

    // ---------- Papelera ----------

    /** Abre la pantalla de la papelera y carga sus elementos (vaciando antes lo que ya tiene más de 30 días). */
    fun openTrash() {
        loadJob?.cancel()
        isLoading.value = false
        clearSelection()
        trashSelection.value = emptySet()
        screenMode.value = ScreenMode.TRASH
        loadTrash()
    }

    private fun loadTrash() {
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    trash.purgeOlderThan(TRASH_DAYS * DAY_MS)
                    trash.list()
                }
                trashEntries.value = entries
                val ids = entries.mapTo(HashSet()) { it.id }
                trashSelection.value = trashSelection.value.filterTo(HashSet()) { it in ids }
            } catch (e: Exception) {
                message.value = "No se pudo leer la papelera"
            }
        }
    }

    fun toggleTrashSelection(entry: TrashBin.Entry) {
        trashSelection.value =
            if (entry.id in trashSelection.value) trashSelection.value - entry.id else trashSelection.value + entry.id
    }

    fun selectAllTrash() {
        trashSelection.value = trashEntries.value.mapTo(HashSet()) { it.id }
    }

    fun clearTrashSelection() {
        if (trashSelection.value.isNotEmpty()) trashSelection.value = emptySet()
    }

    private fun selectedTrash(): List<TrashBin.Entry> =
        trashEntries.value.filter { it.id in trashSelection.value }

    /** Devuelve a su ubicación original (o a la raíz si ya no existe) los elementos seleccionados. */
    fun restoreSelectedFromTrash() {
        val chosen = selectedTrash()
        clearTrashSelection()
        runTasks(chosen, { it.name }, { trash.restore(it, root) }) { loadTrash() }
    }

    fun deleteSelectedFromTrashForever() {
        val chosen = selectedTrash()
        clearTrashSelection()
        runTasks(chosen, { it.name }, { trash.deleteForever(it) }) { loadTrash() }
    }

    fun emptyTrash() {
        clearTrashSelection()
        runTasks(listOf(Unit), { "la papelera" }, { trash.empty() }) { loadTrash() }
    }

    private companion object {
        const val TRASH_DAYS = 30L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
