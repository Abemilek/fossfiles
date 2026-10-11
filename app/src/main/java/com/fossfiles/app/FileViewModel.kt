package com.fossfiles.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ScreenMode { HOME, BROWSE, CATEGORY }
enum class Category(val label: String) { IMAGES("Fotos"), VIDEOS("Videos"), AUDIO("Audio"), DOWNLOADS("Descargas"), DOCUMENTS("Documentos") }

// appContext: SIEMPRE el applicationContext, nunca el de la Activity — así el
// ViewModel jamás retiene una Activity y no hay riesgo de fuga en rotaciones.
class FileViewModel(rootDir: File, private val appContext: Context) : ViewModel() {

    private val root = rootDir
    private var searchJob: Job? = null

    val screenMode = mutableStateOf(ScreenMode.HOME)
    val isLoading = mutableStateOf(false)
    val storageInfo = mutableStateOf(StorageInfo(0, 0))

    val currentDir = mutableStateOf(rootDir)
    val items = mutableStateOf<List<FileItem>>(emptyList())
    val sortMode = mutableStateOf(SortMode.NAME)
    val searchQuery = mutableStateOf("")
    val clipboardItem = mutableStateOf<File?>(null)
    val clipboardIsCut = mutableStateOf(false)

    val categoryTitle = mutableStateOf("")

    init {
        loadStorageInfo()
    }

    private fun loadStorageInfo() {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { StorageStats.read(root) }
            storageInfo.value = info
        }
    }

    // ---------- Home ----------

    fun goHome() {
        screenMode.value = ScreenMode.HOME
        searchQuery.value = ""
        loadStorageInfo()
    }

    fun openInternalStorage() {
        screenMode.value = ScreenMode.BROWSE
        openDirectory(root)
    }

    fun openCategory(category: Category) {
        categoryTitle.value = category.label
        screenMode.value = ScreenMode.CATEGORY
        isLoading.value = true
        viewModelScope.launch {
            val downloads = File(root, "Download")
            val result = withContext(Dispatchers.IO) {
                when (category) {
                    Category.IMAGES -> MediaStoreScanner.images(appContext)
                    Category.VIDEOS -> MediaStoreScanner.videos(appContext)
                    Category.AUDIO -> MediaStoreScanner.audio(appContext)
                    Category.DOWNLOADS -> {
                        val fromIndex = MediaStoreScanner.downloads(appContext)
                        // En versiones viejas de Android, MediaStore.Downloads no existe:
                        // se cae a leer la carpeta directamente.
                        fromIndex.ifEmpty { FileRepository.scanCategory(listOf(downloads), null, maxDepth = 1) }
                    }
                    Category.DOCUMENTS -> FileRepository.scanCategory(
                        listOf(File(root, "Documents"), downloads),
                        setOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx"),
                        maxDepth = 2
                    )
                }
            }
            items.value = result
            isLoading.value = false
        }
    }

    // ---------- Navegación de carpetas ----------

    fun refresh() {
        val dir = currentDir.value
        val query = searchQuery.value
        isLoading.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (query.isBlank()) FileRepository.listDirectory(dir, sortMode.value)
                else FileRepository.search(dir, query)
            }
            items.value = result
            isLoading.value = false
        }
    }

    fun openDirectory(dir: File) {
        currentDir.value = dir
        searchQuery.value = ""
        refresh()
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
        refresh()
    }

    fun setSearch(query: String) {
        searchQuery.value = query
        // Espera un poco a que el usuario deje de escribir antes de buscar,
        // así no se dispara un escaneo por cada letra.
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            refresh()
        }
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { FileRepository.createFolder(currentDir.value, name) }
            refresh()
        }
    }

    fun rename(item: File, newName: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { FileRepository.rename(item, newName) }
            refresh()
        }
    }

    fun delete(item: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { FileRepository.delete(item) }
            refresh()
        }
    }

    fun copyToClipboard(item: File) {
        clipboardItem.value = item
        clipboardIsCut.value = false
    }

    fun cutToClipboard(item: File) {
        clipboardItem.value = item
        clipboardIsCut.value = true
    }

    fun pasteFromClipboard() {
        val source = clipboardItem.value ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (clipboardIsCut.value) FileRepository.move(source, currentDir.value)
                else FileRepository.copy(source, currentDir.value)
            }
            clipboardItem.value = null
            refresh()
        }
    }
}
