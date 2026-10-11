package com.fossfiles.app

import java.io.File

enum class SortMode { NAME, DATE, SIZE, TYPE }

object FileRepository {

    fun listDirectory(dir: File, sortMode: SortMode = SortMode.NAME): List<FileItem> {
        val entries = dir.listFiles()?.map { FileItem(it) } ?: emptyList()
        val sorted = when (sortMode) {
            SortMode.NAME -> entries.sortedBy { it.name.lowercase() }
            SortMode.DATE -> entries.sortedByDescending { it.lastModified }
            SortMode.SIZE -> entries.sortedByDescending { it.sizeBytes }
            SortMode.TYPE -> entries.sortedBy { it.file.extension.lowercase() }
        }
        // Carpetas siempre primero, sea cual sea el orden elegido.
        return sorted.sortedByDescending { it.isDirectory }
    }

    fun createFolder(parent: File, name: String): Boolean {
        val target = File(parent, name)
        return !target.exists() && target.mkdir()
    }

    fun rename(item: File, newName: String): Boolean {
        val target = File(item.parentFile, newName)
        return !target.exists() && item.renameTo(target)
    }

    fun delete(item: File): Boolean {
        return if (item.isDirectory) item.deleteRecursively() else item.delete()
    }

    fun copy(source: File, destinationDir: File): Boolean {
        return try {
            val target = File(destinationDir, source.name)
            if (source.isDirectory) {
                source.copyRecursively(target, overwrite = false)
            } else {
                source.copyTo(target, overwrite = false)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun move(source: File, destinationDir: File): Boolean {
        val copied = copy(source, destinationDir)
        return copied && delete(source)
    }

    fun search(root: File, query: String): List<FileItem> {
        val results = mutableListOf<FileItem>()
        root.walkTopDown()
            .maxDepth(6) // evita colgarse buscando en árboles gigantes
            .filter { it != root && it.name.contains(query, ignoreCase = true) }
            .forEach { results.add(FileItem(it)) }
        return results
    }

    /**
     * Escanea un set fijo de carpetas conocidas (no todo el almacenamiento) y
     * filtra por extensión. Así se arma la vista "Fotos", "Videos", etc. sin
     * tener que recorrer miles de archivos irrelevantes cada vez.
     */
    fun scanCategory(dirs: List<File>, extensions: Set<String>?, maxDepth: Int = 4): List<FileItem> {
        val results = mutableListOf<FileItem>()
        for (dir in dirs) {
            if (!dir.exists()) continue
            dir.walkTopDown()
                .maxDepth(maxDepth)
                .filter { it.isFile }
                .filter { extensions == null || it.extension.lowercase() in extensions }
                .forEach { results.add(FileItem(it)) }
        }
        return results.sortedByDescending { it.lastModified }
    }
}
