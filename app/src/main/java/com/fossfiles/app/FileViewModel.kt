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

enum class ScreenMode { HOME, BROWSE, CATEGORY, TRASH, SETTINGS }
enum class Category(val label: String) {
    IMAGES("Fotos"), VIDEOS("Videos"), AUDIO("Audio"), DOWNLOADS("Descargas"), DOCUMENTS("Documentos"), FAVORITES("Favoritos")
}

enum class ThemeMode(val label: String) { SYSTEM("Del sistema"), LIGHT("Claro"), DARK("Oscuro") }

/** Imágenes que se pueden recorrer en el visor y cuál se está viendo. */
data class ViewerState(val images: List<FileItem>, val index: Int)

/** Lista de audio/video del reproductor y cuál suena. */
data class MediaState(val items: List<FileItem>, val index: Int)

/** Acceso rápido a una carpeta conocida del almacenamiento. */
data class QuickLink(val label: String, val dir: File)

/** Un volumen (interno o tarjeta SD) con cuánto espacio lleva usado. */
data class VolumeUsage(val volume: StorageVolumeInfo, val usage: StorageInfo)

/** Lo que muestra la franja de progreso: [fraction] es null cuando no se conoce el total. */
data class ProgressInfo(val title: String, val fraction: Float?, val detail: String)

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

    // Raíces de todos los volúmenes montados (la lee también el hilo de fondo).
    @Volatile private var volumeRoots: List<File> = listOf(rootDir)

    // Hasta dónde se puede subir con "carpeta superior": la raíz del volumen que se está explorando.
    private var browseRoot: File = rootDir

    // Preferencias solo locales (SharedPreferences privado de la app): vista, ocultos, orden, tema...
    private val prefs = appContext.getSharedPreferences("fossfiles", Context.MODE_PRIVATE)

    // Solo hay una carga (listado, búsqueda o categoría) viva a la vez: al lanzar otra,
    // la anterior se cancela de verdad (incluido el recorrido de disco), así una búsqueda
    // vieja nunca puede pisar los resultados de una nueva.
    private var loadJob: Job? = null
    private var detailsJob: Job? = null
    private var currentProgress: OpProgress? = null
    private var anchorPath: String? = null // último elemento marcado: punto de partida de la selección por rango

    // Posición de desplazamiento de las carpetas ya visitadas, para volver al mismo punto al regresar.
    private val scrollMemory = HashMap<String, IntArray>()

    val screenMode = mutableStateOf(ScreenMode.HOME)
    val isLoading = mutableStateOf(false)
    /** true solo si la carga tarda más de 250 ms (así no parpadea el aviso en carpetas rápidas). */
    val loadingVisible = mutableStateOf(false)
    val isWorking = mutableStateOf(false) // copiando / moviendo / borrando / comprimiendo
    val progressInfo = mutableStateOf<ProgressInfo?>(null)

    val volumes = mutableStateOf<List<VolumeUsage>>(emptyList())
    val quickLinks = mutableStateOf<List<QuickLink>>(emptyList())
    val recent = mutableStateOf<List<FileItem>>(emptyList())

    val currentDir = mutableStateOf(rootDir)
    val items = mutableStateOf<List<FileItem>>(emptyList())
    /** Carpeta a la que pertenece [items] (null en categorías). Si no coincide con [currentDir], la lista aún es la anterior. */
    val loadedDir = mutableStateOf<File?>(null)

    val sortMode = mutableStateOf(savedSort())
    val sortReverse = mutableStateOf(prefs.getBoolean("reverse", false))
    val gridView = mutableStateOf(prefs.getBoolean("grid", false))
    val showHidden = mutableStateOf(prefs.getBoolean("hidden", false))
    val searchQuery = mutableStateOf("")

    val themeMode = mutableStateOf(
        try { ThemeMode.valueOf(prefs.getString("theme", null) ?: "SYSTEM") } catch (e: IllegalArgumentException) { ThemeMode.SYSTEM }
    )
    val trashDays = mutableStateOf(prefs.getInt("trashDays", 30))
    val crashText = mutableStateOf<String?>(null)

    val currentCategory = mutableStateOf<Category?>(null)
    val categoryTitle = mutableStateOf("")

    /** Rutas absolutas de los elementos seleccionados. */
    val selection = mutableStateOf<Set<String>>(emptySet())

    val clipboard = mutableStateOf<List<File>>(emptyList())
    val clipboardIsCut = mutableStateOf(false)

    val details = mutableStateOf<FileDetails?>(null)
    val viewer = mutableStateOf<ViewerState?>(null)
    val zipPrompt = mutableStateOf<FileItem?>(null)

    val media = mutableStateOf<MediaState?>(null)
    // Al terminar una pista pasa sola a la siguiente de la lista (si hay).
    val player = PlayerController(appContext) { advanceAfterCompletion() }

    val filter = mutableStateOf(SearchFilter())

    /** Rutas absolutas de los favoritos (guardadas solo en el almacenamiento privado de la app). */
    val favorites = mutableStateOf<Set<String>>(HashSet(prefs.getStringSet("fav", emptySet()) ?: emptySet()))

    val trashEntries = mutableStateOf<List<TrashBin.Entry>>(emptyList())
    /** false mientras se abre la papelera y aún no se leyó (así no parpadea «vacía»). */
    val trashLoaded = mutableStateOf(false)
    val trashSelection = mutableStateOf<Set<String>>(emptySet()) // rutas de las entradas

    /** Mensaje de error/aviso para mostrar una vez; la UI lo limpia con [dismissMessage]. */
    val message = mutableStateOf<String?>(null)

    val appVersion: String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    init {
        refreshHome()
    }

    private fun savedSort(): SortMode =
        try { SortMode.valueOf(prefs.getString("sort", null) ?: "NAME") } catch (e: IllegalArgumentException) { SortMode.NAME }

    // ---------- Inicio ----------

    /** Actualiza lo que muestra el Home: volúmenes, accesos rápidos y recientes. */
    fun refreshHome() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val found = StorageVolumes.list(appContext)
                volumeRoots = found.map { it.root }
                val usage = found.map { v ->
                    VolumeUsage(v, try { StorageStats.read(v.root) } catch (e: Exception) { StorageInfo(0, 0) })
                }
                val known = listOf(
                    "Descargas" to "Download",
                    "Cámara" to "DCIM",
                    "Imágenes" to "Pictures",
                    "Documentos" to "Documents",
                    "Música" to "Music",
                    "Películas" to "Movies"
                )
                val links = known.map { QuickLink(it.first, File(root, it.second)) }.filter { it.dir.isDirectory }
                purgeTrash()
                Triple(usage, links, MediaStoreScanner.recent(appContext))
            }
            volumes.value = snapshot.first
            quickLinks.value = snapshot.second
            recent.value = snapshot.third
        }
    }

    fun dismissMessage() { message.value = null }

    // ---------- Carga ----------

    private fun launchLoad(debounceMs: Long = 0L, forDir: File? = null, work: (isActive: () -> Boolean) -> Loaded) {
        loadJob?.cancel()
        isLoading.value = true
        val job = viewModelScope.launch {
            try {
                if (debounceMs > 0) delay(debounceMs)
                val loaded = withContext(Dispatchers.IO) { work { isActive } }
                items.value = loaded.items
                loadedDir.value = forDir
                // Si algo de lo seleccionado ya no está en la lista, se quita de la selección.
                if (selection.value.isNotEmpty()) {
                    val present = HashSet<String>()
                    loaded.items.forEach { present.add(it.path) }
                    selection.value = selection.value.filterTo(HashSet()) { it in present }
                }
                loaded.notice?.let { message.value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                items.value = emptyList()
                loadedDir.value = forDir
                message.value = e.message ?: "No se pudo leer esta ubicación"
            }
        }
        loadJob = job
        job.invokeOnCompletion {
            if (loadJob === job) {
                isLoading.value = false
                loadingVisible.value = false
            }
        }
        // El aviso "Cargando…" solo aparece si tarda: en carpetas normales no se ve ni parpadea.
        viewModelScope.launch {
            delay(250)
            if (loadJob === job && job.isActive) loadingVisible.value = true
        }
    }

    private fun loadDirectory(debounceMs: Long = 0L) {
        val dir = currentDir.value
        val query = searchQuery.value.trim()
        val mode = sortMode.value
        val reverse = sortReverse.value
        val hidden = showHidden.value
        val activeFilter = filter.value
        launchLoad(debounceMs, forDir = dir) { active ->
            if (query.isEmpty() && !activeFilter.isActive) {
                Loaded(FileRepository.listDirectory(dir, mode, hidden, reverse))
            } else {
                // Con texto o con filtros activos se busca también en las subcarpetas.
                val r = FileRepository.search(dir, query, mode, active, hidden, activeFilter, reverse)
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
        return FileRepository.sortItems(existing.map { FileItem.of(it) }, SortMode.NAME)
    }

    /** Vuelve a cargar lo que se esté viendo (carpeta, búsqueda, categoría o papelera). */
    fun reload() {
        when (screenMode.value) {
            ScreenMode.BROWSE -> loadDirectory()
            ScreenMode.CATEGORY -> currentCategory.value?.let { loadCategory(it) }
            ScreenMode.HOME -> refreshHome()
            ScreenMode.TRASH -> loadTrash()
            ScreenMode.SETTINGS -> Unit
        }
    }

    // ---------- Navegación ----------

    fun goHome() {
        loadJob?.cancel()
        isLoading.value = false
        loadingVisible.value = false
        clearSelection()
        trashSelection.value = emptySet()
        screenMode.value = ScreenMode.HOME
        searchQuery.value = ""
        filter.value = SearchFilter()
        // "Archivos grandes" ordena por tamaño sin guardarlo: al volver se recupera el orden elegido.
        sortMode.value = savedSort()
        sortReverse.value = prefs.getBoolean("reverse", false)
        refreshHome()
    }

    /** Abre un volumen (almacenamiento interno o tarjeta SD) en el explorador. */
    fun openVolume(volume: StorageVolumeInfo) {
        browseRoot = volume.root
        screenMode.value = ScreenMode.BROWSE
        openDirectory(volume.root)
    }

    fun openInternalStorage() {
        browseRoot = root
        screenMode.value = ScreenMode.BROWSE
        openDirectory(root)
    }

    fun openQuickLink(link: QuickLink) = openFolder(link.dir)

    /** Abre una carpeta en el explorador desde cualquier pantalla (Home, favoritos, accesos rápidos). */
    fun openFolder(dir: File) {
        browseRoot = VolumePaths.volumeOf(dir, volumeRoots) ?: root
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

    /** true si subió de nivel, false si ya estaba en la raíz del volumen (para volver al Home) */
    fun goUp(): Boolean {
        val parent = currentDir.value.parentFile
        return if (parent != null && currentDir.value != browseRoot) {
            openDirectory(parent)
            true
        } else {
            false
        }
    }

    /** Búsqueda desde el Home: busca por nombre en todo el almacenamiento interno. */
    fun searchFromHome(query: String) {
        val text = query.trim()
        if (text.isEmpty()) return
        browseRoot = root
        screenMode.value = ScreenMode.BROWSE
        clearSelection()
        currentDir.value = root
        filter.value = SearchFilter()
        searchQuery.value = text
        loadDirectory()
    }

    /** "Archivos grandes": todo lo que pese más de 100 MB, de mayor a menor, para liberar espacio. */
    fun openLargeFiles() {
        browseRoot = root
        screenMode.value = ScreenMode.BROWSE
        clearSelection()
        currentDir.value = root
        searchQuery.value = ""
        sortMode.value = SortMode.SIZE
        sortReverse.value = false
        filter.value = SearchFilter(size = SizeFilter.OVER_100MB)
        loadDirectory()
    }

    // ---------- Posición al volver a una carpeta ----------

    fun rememberScroll(dir: File, index: Int, offset: Int) {
        if (scrollMemory.size > 200) scrollMemory.clear() // acotado: no crece sin límite
        scrollMemory[dir.path] = intArrayOf(index, offset)
    }

    fun scrollFor(dir: File): IntArray? = scrollMemory[dir.path]

    // ---------- Preferencias de vista ----------

    fun setSort(mode: SortMode) {
        sortMode.value = mode
        prefs.edit().putString("sort", mode.name).apply()
        loadDirectory()
    }

    fun toggleSortReverse() {
        sortReverse.value = !sortReverse.value
        prefs.edit().putBoolean("reverse", sortReverse.value).apply()
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

    // ---------- Ajustes ----------

    fun openSettings() {
        loadJob?.cancel()
        isLoading.value = false
        loadingVisible.value = false
        clearSelection()
        screenMode.value = ScreenMode.SETTINGS
    }

    fun cycleTheme() {
        themeMode.value = next(ThemeMode.values(), themeMode.value)
        prefs.edit().putString("theme", themeMode.value.name).apply()
    }

    fun cycleTrashDays() {
        val options = listOf(7, 15, 30, 60, 90)
        val index = options.indexOf(trashDays.value)
        trashDays.value = options[(index + 1) % options.size] // si no estaba en la lista (-1) pasa a la primera
        prefs.edit().putInt("trashDays", trashDays.value).apply()
    }

    fun clearThumbnailCache() {
        ThumbnailLoader.clear()
        message.value = "Caché de miniaturas vaciada"
    }

    fun showCrashLog() {
        val text = CrashLog.read(appContext)
        if (text == null) message.value = "No hay errores registrados" else crashText.value = text
    }

    fun closeCrashLog() { crashText.value = null }

    fun clearCrashLog() {
        CrashLog.clear(appContext)
        crashText.value = null
        message.value = "Registro de errores borrado"
    }

    // ---------- Selección ----------

    fun toggleSelection(item: FileItem) {
        val path = item.path
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
        val path = item.path
        val to = list.indexOfFirst { it.path == path }
        if (to < 0) return
        val anchor = anchorPath
        val from = if (anchor == null) -1 else list.indexOfFirst { it.path == anchor }
        if (from < 0) {
            toggleSelection(item)
            return
        }
        val range = if (from <= to) from..to else to..from
        selection.value = selection.value + range.map { list[it].path }
        anchorPath = path
    }

    /** Marca todos los elementos que no estaban seleccionados y desmarca los que sí. */
    fun invertSelection() {
        val all = items.value.mapTo(HashSet()) { it.path }
        selection.value = all - selection.value
    }

    fun selectAll() {
        selection.value = items.value.mapTo(HashSet()) { it.path }
    }

    fun clearSelection() {
        anchorPath = null
        if (selection.value.isNotEmpty()) selection.value = emptySet()
    }

    private fun selectedFiles(): List<File> =
        items.value.filter { it.path in selection.value }.map { it.file }

    // ---------- Operaciones ----------

    /**
     * Ejecuta [op] para cada elemento en segundo plano. Mientras dura, la franja de progreso (que solo
     * aparece si tarda más de unos instantes) muestra avance y un botón para cancelar. Al final avisa
     * al índice multimedia de Android de lo que cambió y llama a [onDone].
     */
    private fun <T> runTasks(
        items: List<T>,
        title: String,
        label: (T) -> String,
        bytesOf: (List<T>, OpProgress) -> Long,
        op: (T, OpProgress) -> Unit,
        onDone: () -> Unit
    ) {
        if (items.isEmpty() || isWorking.value) return
        isWorking.value = true
        val progress = OpProgress()
        progress.itemsTotal = items.size
        currentProgress = progress

        val ticker = viewModelScope.launch {
            delay(400) // las operaciones cortas terminan antes y nunca muestran la franja
            while (true) {
                publishProgress(title, progress)
                delay(300)
            }
        }
        viewModelScope.launch {
            val errors = withContext(Dispatchers.IO) {
                progress.totalBytes = try { bytesOf(items, progress) } catch (e: Exception) { 0L }
                val failed = ArrayList<String>()
                for (item in items) {
                    if (progress.cancelled) break
                    try {
                        op(item, progress)
                    } catch (e: FileOpException) {
                        if (e.message != OpProgress.CANCELLED_MESSAGE) failed.add(e.message ?: "Error")
                    } catch (e: Exception) {
                        failed.add("Error inesperado con «${label(item)}»")
                    }
                    progress.itemsDone++
                }
                MediaRefresh.scan(appContext, progress.touched())
                failed
            }
            ticker.cancel()
            progressInfo.value = null
            currentProgress = null
            isWorking.value = false
            if (progress.cancelled) {
                message.value = OpProgress.CANCELLED_MESSAGE
            } else if (errors.isNotEmpty()) {
                message.value = errors.first() + if (errors.size > 1) " (y ${errors.size - 1} errores más)" else ""
            }
            onDone()
        }
    }

    private fun publishProgress(title: String, p: OpProgress) {
        val total = p.totalBytes
        val done = p.bytesDone
        val current = (p.itemsDone + 1).coerceAtMost(p.itemsTotal)
        var detail = if (p.itemsTotal > 1) "$current de ${p.itemsTotal}" else ""
        if (total > 0) {
            if (detail.isNotEmpty()) detail += " · "
            detail += "${formatSize(done)} de ${formatSize(total)}"
        }
        progressInfo.value = ProgressInfo(
            title = title,
            fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
            detail = detail
        )
    }

    /** El usuario pulsó «Cancelar» en la franja de progreso. */
    fun cancelWork() {
        currentProgress?.cancelled = true
    }

    private fun runBatch(
        files: List<File>,
        title: String,
        bytesOf: (List<File>, OpProgress) -> Long = { _, _ -> 0L },
        op: (File, OpProgress) -> Unit
    ) = runTasks(files, title, { it.name }, bytesOf, op) { reload() }

    private fun totalSize(files: List<File>, p: OpProgress): Long = FileRepository.totalBytes(files) { !p.cancelled }

    fun createFolder(name: String) {
        val parent = currentDir.value
        runBatch(listOf(parent), "Creando carpeta") { f, _ -> FileRepository.createFolder(f, name) }
    }

    fun rename(item: File, newName: String) {
        clearSelection()
        runBatch(listOf(item), "Renombrando") { f, p ->
            val renamed = FileRepository.rename(f, newName)
            p.touch(f.path)
            p.touch(renamed.path)
            if (f.absolutePath in favorites.value) {
                updateFavorites { favs -> favs - f.absolutePath + renamed.absolutePath }
            }
        }
    }

    /** Envía a la papelera (se puede restaurar) o, con [permanent], borra para siempre. */
    fun deleteSelected(permanent: Boolean) {
        val files = selectedFiles()
        clearSelection()
        runBatch(files, if (permanent) "Eliminando" else "Enviando a la papelera") { f, p ->
            if (permanent) FileRepository.delete(f, p) else binFor(f).moveToTrash(f, progress = p)
            if (f.absolutePath in favorites.value) updateFavorites { favs -> favs - f.absolutePath }
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

    fun clearClipboard() {
        clipboard.value = emptyList()
    }

    fun pasteHere() {
        val sources = clipboard.value
        if (sources.isEmpty() || screenMode.value != ScreenMode.BROWSE) return
        val dest = currentDir.value
        val cut = clipboardIsCut.value
        clipboard.value = emptyList()
        // Mover dentro del mismo volumen es un renombrado instantáneo (sin bytes que contar);
        // copiar, o mover a otro volumen, sí copia datos y por eso muestra el avance en bytes.
        val destVolume = VolumePaths.volumeOf(dest, volumeRoots)
        val copiesData = !cut || sources.any { VolumePaths.volumeOf(it, volumeRoots) != destVolume }
        runBatch(
            sources,
            if (cut) "Moviendo" else "Copiando",
            bytesOf = { files, p -> if (copiesData) totalSize(files, p) else 0L }
        ) { f, p ->
            if (cut) FileRepository.move(f, dest, p) else FileRepository.copy(f, dest, p)
        }
    }

    /** Comprime lo seleccionado en un .zip dentro de la carpeta actual. */
    fun compressSelected() {
        if (screenMode.value != ScreenMode.BROWSE) return
        val files = selectedFiles()
        val dest = currentDir.value
        clearSelection()
        runTasks(listOf(dest), "Comprimiendo", { it.name }, { _, p -> totalSize(files, p) }, { d, p ->
            FileRepository.zip(files, d, p)
        }) { reload() }
    }

    /** Extrae cada ZIP seleccionado en una carpeta nueva junto a él. */
    fun extractSelected() {
        val zips = selectedFiles().filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
        clearSelection()
        extractZips(zips)
    }

    /** Extrae el ZIP que el usuario tocó (tras confirmar en el aviso). */
    fun extractZip(item: FileItem) {
        zipPrompt.value = null
        extractZips(listOf(item.file))
    }

    private fun extractZips(zips: List<File>) {
        runBatch(zips, "Extrayendo", bytesOf = { files, _ -> files.sumOf { FileRepository.zipUncompressedSize(it) } }) { f, p ->
            val out = FileRepository.unzip(f, p)
            message.value = "Extraído en «${out.name}»"
        }
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

    // ---------- Papelera (una por volumen, para que mover a ella sea siempre instantáneo) ----------

    private fun trashDirOf(volumeRoot: File) = File(volumeRoot, ".fossfiles-trash")

    private fun allBins(): List<TrashBin> = volumeRoots.map { TrashBin(trashDirOf(it)) }

    /** La papelera del volumen donde está [file]. */
    private fun binFor(file: File): TrashBin = TrashBin(trashDirOf(VolumePaths.volumeOf(file, volumeRoots) ?: root))

    private fun purgeTrash() {
        val maxAge = trashDays.value * DAY_MS
        for (bin in allBins()) {
            try { bin.purgeOlderThan(maxAge) } catch (e: Exception) { /* no es crítico */ }
        }
    }

    /** Abre la pantalla de la papelera y carga sus elementos (vaciando antes lo que superó los días configurados). */
    fun openTrash() {
        loadJob?.cancel()
        isLoading.value = false
        loadingVisible.value = false
        clearSelection()
        trashSelection.value = emptySet()
        screenMode.value = ScreenMode.TRASH
        trashLoaded.value = false
        loadTrash()
    }

    private fun loadTrash() {
        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    purgeTrash()
                    allBins().flatMap { it.list() }.sortedByDescending { it.deletedAt }
                }
                trashEntries.value = entries
                trashLoaded.value = true
                val keys = entries.mapTo(HashSet()) { it.file.absolutePath }
                trashSelection.value = trashSelection.value.filterTo(HashSet()) { it in keys }
            } catch (e: Exception) {
                trashLoaded.value = true
                message.value = "No se pudo leer la papelera"
            }
        }
    }

    fun toggleTrashSelection(entry: TrashBin.Entry) {
        val key = entry.file.absolutePath
        trashSelection.value =
            if (key in trashSelection.value) trashSelection.value - key else trashSelection.value + key
    }

    fun selectAllTrash() {
        trashSelection.value = trashEntries.value.mapTo(HashSet()) { it.file.absolutePath }
    }

    fun clearTrashSelection() {
        if (trashSelection.value.isNotEmpty()) trashSelection.value = emptySet()
    }

    private fun selectedTrash(): List<TrashBin.Entry> =
        trashEntries.value.filter { it.file.absolutePath in trashSelection.value }

    /** Devuelve a su ubicación original (o a la raíz de su volumen si ya no existe) los elementos seleccionados. */
    fun restoreSelectedFromTrash() {
        val chosen = selectedTrash()
        clearTrashSelection()
        runTasks(chosen, "Restaurando", { it.name }, { _, _ -> 0L }, { entry, p ->
            // <volumen>/.fossfiles-trash/<id>/<nombre>: tres niveles hasta la raíz del volumen
            val volumeRoot = entry.file.parentFile?.parentFile?.parentFile ?: root
            TrashBin.of(entry)?.restore(entry, volumeRoot, p)
        }) { loadTrash() }
    }

    fun deleteSelectedFromTrashForever() {
        val chosen = selectedTrash()
        clearTrashSelection()
        runTasks(chosen, "Eliminando", { it.name }, { _, _ -> 0L }, { entry, _ ->
            TrashBin.of(entry)?.deleteForever(entry)
        }) { loadTrash() }
    }

    fun emptyTrash() {
        clearTrashSelection()
        runTasks(listOf(Unit), "Vaciando la papelera", { "la papelera" }, { _, _ -> 0L }, { _, _ ->
            allBins().forEach { it.empty() }
        }) { loadTrash() }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
