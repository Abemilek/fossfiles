package com.fossfiles.app

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDirectory: Boolean = file.isDirectory,
    val sizeBytes: Long = if (isDirectory) 0L else file.length(),
    val lastModified: Long = file.lastModified()
) {
    /**
     * Ruta absoluta calculada una sola vez: File.getAbsolutePath() crea un String nuevo en cada
     * llamada y la UI la consulta por fila (claves, selección, favoritos).
     */
    val path: String = file.absolutePath

    // Caché del tipo en un campo simple (no entra en equals/copy). Antes era `by lazy`, que reserva
    // un objeto Lazy + una lambda por cada archivo (decenas de miles en carpetas grandes). Si dos
    // hilos lo calculan a la vez el resultado es idéntico, así que la carrera es inofensiva.
    private var kindCache: FileKind? = null

    /** El tipo se calcula la primera vez que se pide y se reutiliza. */
    val cachedKind: FileKind
        get() = kindCache ?: computeKind().also { kindCache = it }

    private fun computeKind(): FileKind {
        if (isDirectory) return FileKind.FOLDER
        return when (file.extension.lowercase()) {
            in IMAGE_EXTENSIONS -> FileKind.IMAGE
            in VIDEO_EXTENSIONS -> FileKind.VIDEO
            in AUDIO_EXTENSIONS -> FileKind.AUDIO
            in DOCUMENT_EXTENSIONS -> FileKind.DOCUMENT
            in ARCHIVE_EXTENSIONS -> FileKind.ARCHIVE
            "apk" -> FileKind.APK
            else -> FileKind.OTHER
        }
    }

    companion object {
        /**
         * Igual que FileItem(file) pero con UNA sola llamada al sistema (stat) en vez de tres
         * (isDirectory + length + lastModified): en carpetas de miles de archivos se nota.
         */
        fun of(file: File): FileItem = try {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            val dir = attrs.isDirectory
            FileItem(
                file = file,
                name = file.name,
                isDirectory = dir,
                sizeBytes = if (dir) 0L else attrs.size(),
                lastModified = attrs.lastModifiedTime().toMillis()
            )
        } catch (e: IOException) {
            FileItem(file) // p. ej. enlace roto: se leen los datos por separado, como antes
        }

        /**
         * Como `if (file.isFile) of(file) else null` pero con UNA sola llamada al sistema en vez de dos
         * (isFile + stat). Sigue enlaces simbólicos igual que File.isFile. Si el sistema no puede leer
         * los atributos, vuelve al comportamiento anterior.
         */
        fun regularFileOrNull(file: File): FileItem? = try {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            if (!attrs.isRegularFile) {
                null
            } else {
                FileItem(
                    file = file,
                    name = file.name,
                    isDirectory = false,
                    sizeBytes = attrs.size(),
                    lastModified = attrs.lastModifiedTime().toMillis()
                )
            }
        } catch (e: IOException) {
            if (file.isFile) FileItem(file) else null
        }
    }
}

enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, OTHER }

val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")
val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v")
val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr")
val DOCUMENT_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx",
    "odt", "ods", "odp", "csv", "rtf", "epub", "md"
)
val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")

fun FileItem.kind(): FileKind = cachedKind

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${value.toInt()} ${units[unitIndex]}"
    else "%.1f %s".format(value, units[unitIndex])
}
