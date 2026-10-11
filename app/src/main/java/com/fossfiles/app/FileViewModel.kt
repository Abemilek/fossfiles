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

private class Loaded(val items: List<FileItem>, val notice: String? = null)

// appContext: SIEMPRE el applicationContext, nunca el de la Activity — así el
// ViewModel jamás retiene una Activity y no hay riesgo de fuga en rotaciones.
class FileViewModel(rootDir: File, private val appContext: Context) : ViewModel() {

    private val root = rootDir

    // Solo hay una carga (listado, búsqueda o categoría) viva a la vez: al lanzar otra,
    // la anterior se cancela de verdad (incluido el recorrido de disco), así una búsqueda
    // vieja nunca puede pisar los resultados de una nueva.
    private var loadJob: Job? = null

    val screenMode = mutableStateOf(ScreenMode.HOME)
    val isLoading = mutableStateOf(false)
    val isWorking = mutableStateOf(false) // copiando / moviendo / borrando
    val storageInfo = mutableStateOf(StorageInfo(0, 0))

    val currentDir = mutableStateOf(rootDir)
    val items = mutableStateOf<List<FileItem>>(emptyList())
    val sortMode = mutableStateOf(SortMode.NAME)
    val searchQuery = mutableStateOf("")

    val currentCategory = mutableStateOf<Category?>(null)
    val categoryTitle = mutableStateOf("")

    /** Rutas absolutas de los elementos seleccionados. */
    val selection = mutableStateOf<Set<String>>(emptySet())

    val clipboard = mutableStateOf<List<File>>(emptyList())
    val clipboardIsCut = mutableStateOf(false)

    /** Mensaje de error/aviso para mostrar una vez; la UI lo limpia con [dismissMessage]. */
    val message = mutableStateOf<String?>(null)

    init {
        loadStorageInfo()
    }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { StorageStats.read(root) }
            storageInfo.value = info
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
                val loaded = withContext(Dispatchers.IO) { work { this@launch.isActive } }
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
        launchLoad(debounceMs) { active ->
            if (query.isEmpty()) {
                Loaded(FileRepository.listDirectory(dir, mode))
            } else {
                val r = FileRepository.search(dir, query, mode, active)
                Loaded(
                    r.items,
                    if (r.truncated) "Mostrando solo los primeros ${FileRepository.MAX_SEARCH_RESULTS} resultados" else null
                )
            }
        }
    }

    private fun loadCategory(category: Category) {
        val downloads = File(root, "Download")
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
                            FileRepository.scanCategory(listOf(downloads), null, maxDepth = 1, isActive = active)
                        }
                    Category.DOCUMENTS -> FileRepository.scanCategory(
                        listOf(File(root, "Documents"), downloads),
                        DOCUMENT_EXTENSIONS,
                        maxDepth = 2,
                        isActive = active
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
            ScreenMode.HOME -> loadStorageInfo()
        }
    }

    // ---------- Navegación ----------

    fun goHome() {
        loadJob?.cancel()
        isLoading.value = false
        clearSelection()
        screenMode.value = ScreenMode.HOME
        searchQuery.value = ""
        loadStorageInfo()
    }

    fun openInternalStorage() {
        screenMode.value = ScreenMode.BROWSE
        openDirectory(root)
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

    fun setSort(mode: SortMode) {
        sortMode.value = mode
        loadDirectory()
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
}
