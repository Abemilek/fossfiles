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

enum class ScreenMode { HOME, BROWSE, CATEGORY }
enum class Category(val label: String) { IMAGES("Fotos"), VIDEOS("Videos"), AUDIO("Audio"), DOWNLOADS("Descargas"), DOCUMENTS("Documentos") }

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

    // Preferencias solo locales (SharedPreferences privado de la app): vista, ocultos y orden.
    private val prefs = appContext.getSharedPreferences("fossfiles", Context.MODE_PRIVATE)

    // Solo hay una carga (listado, búsqueda o categoría) viva a la vez: al lanzar otra,
    // la anterior se cancela de verdad (incluido el recorrido de disco), así una búsqueda
    // vieja nunca puede pisar los resultados de una nueva.
    private var loadJob: Job? = null
    private var detailsJob: Job? = null

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
        launchLoad(debounceMs) { active ->
            if (query.isEmpty()) {
                Loaded(FileRepository.listDirectory(dir, mode, hidden))
            } else {
                val r = FileRepository.search(dir, query, mode, active, hidden)
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
                }
            )
        }
    }

    /** Vuelve a cargar lo que se esté viendo (carpeta, búsqueda o categoría). */
    fun reload() {
        when (screenMode.value) {
            ScreenMode.BROWSE -> loadDirectory()
            ScreenMode.CATEGORY -> currentCategory.value?.let { loadCategory(it) }
            ScreenMode.HOME -> refreshHome()
        }
    }

    // ---------- Navegación ----------

    fun goHome() {
        loadJob?.cancel()
        isLoading.value = false
        clearSelection()
        screenMode.value = ScreenMode.HOME
        searchQuery.value = ""
        refreshHome()
    }

    fun openInternalStorage() {
        screenMode.value = ScreenMode.BROWSE
        openDirectory(root)
    }

    fun openQuickLink(link: QuickLink) {
        screenMode.value = ScreenMode.BROWSE
        openDirectory(link.dir)
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

    // ---------- Selección ----------

    fun toggleSelection(item: FileItem) {
        val path = item.file.absolutePath
        selection.value = if (path in selection.value) selection.value - path else selection.value + path
    }

    fun selectAll() {
        selection.value = items.value.mapTo(HashSet()) { it.file.absolutePath }
    }

    fun clearSelection() {
        if (selection.value.isNotEmpty()) selection.value = emptySet()
    }

    private fun selectedFiles(): List<File> =
        items.value.filter { it.file.absolutePath in selection.value }.map { it.file }

    // ---------- Operaciones ----------

    /** Ejecuta [op] para cada archivo en segundo plano, junta los errores y recarga la vista. */
    private fun runBatch(files: List<File>, op: (File) -> Unit) {
        if (files.isEmpty() || isWorking.value) return
        isWorking.value = true
        viewModelScope.launch {
            val errors = withContext(Dispatchers.IO) {
                files.mapNotNull { f ->
                    try {
                        op(f)
                        null
                    } catch (e: FileOpException) {
                        e.message
                    } catch (e: Exception) {
                        "Error inesperado con «${f.name}»"
                    }
                }
            }
            isWorking.value = false
            if (errors.isNotEmpty()) {
                message.value = errors.first() +
                    if (errors.size > 1) " (y ${errors.size - 1} errores más)" else ""
            }
            reload()
        }
    }

    fun createFolder(name: String) {
        val parent = currentDir.value
        runBatch(listOf(parent)) { FileRepository.createFolder(it, name) }
    }

    fun rename(item: File, newName: String) {
        clearSelection()
        runBatch(listOf(item)) { FileRepository.rename(it, newName) }
    }

    fun deleteSelected() {
        val files = selectedFiles()
        clearSelection()
        runBatch(files) { FileRepository.delete(it) }
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
}
