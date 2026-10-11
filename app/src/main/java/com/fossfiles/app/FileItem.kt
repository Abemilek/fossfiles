package com.fossfiles.app

import java.io.File

data class FileItem(
    val file: File,
    val name: String = file.name,
    val isDirectory: Boolean = file.isDirectory,
    val sizeBytes: Long = if (isDirectory) 0L else file.length(),
    val lastModified: Long = file.lastModified()
)

enum class FileKind { FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, OTHER }

val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")
val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v")
val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr")
val DOCUMENT_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx",
    "odt", "ods", "odp", "csv", "rtf", "epub", "md"
)

fun FileItem.kind(): FileKind {
    if (isDirectory) return FileKind.FOLDER
    val ext = file.extension.lowercase()
    return when (ext) {
        in IMAGE_EXTENSIONS -> FileKind.IMAGE
        in VIDEO_EXTENSIONS -> FileKind.VIDEO
        in AUDIO_EXTENSIONS -> FileKind.AUDIO
        in DOCUMENT_EXTENSIONS -> FileKind.DOCUMENT
        else -> FileKind.OTHER
    }
}

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
