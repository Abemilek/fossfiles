package com.fossfiles.app

// Los nombres que ve el usuario (y su idioma) los pone la pantalla; aquí solo está la lógica.
enum class TypeFilter {
    ALL, FOLDERS, IMAGES, VIDEOS, AUDIO, DOCUMENTS
}

enum class DateFilter(val days: Int?) {
    ANY(null), DAY(1), WEEK(7), MONTH(30), YEAR(365)
}

private const val MB = 1024L * 1024L

enum class SizeFilter(val minBytes: Long) {
    ANY(0L), OVER_1MB(MB), OVER_10MB(10 * MB), OVER_100MB(100 * MB), OVER_1GB(1024 * MB)
}

/** Filtros combinables (todos deben cumplirse). Solo usa tipos de Java/Kotlin, se prueba en la JVM. */
data class SearchFilter(
    val type: TypeFilter = TypeFilter.ALL,
    val date: DateFilter = DateFilter.ANY,
    val size: SizeFilter = SizeFilter.ANY
) {
    val isActive: Boolean
        get() = type != TypeFilter.ALL || date != DateFilter.ANY || size != SizeFilter.ANY

    fun matches(item: FileItem, now: Long = System.currentTimeMillis()): Boolean {
        val kind = item.kind()
        val typeOk = when (type) {
            TypeFilter.ALL -> true
            TypeFilter.FOLDERS -> item.isDirectory
            TypeFilter.IMAGES -> kind == FileKind.IMAGE
            TypeFilter.VIDEOS -> kind == FileKind.VIDEO
            TypeFilter.AUDIO -> kind == FileKind.AUDIO
            TypeFilter.DOCUMENTS -> kind == FileKind.DOCUMENT
        }
        if (!typeOk) return false
        val days = date.days
        if (days != null && item.lastModified < now - days * 24L * 60 * 60 * 1000) return false
        // El tamaño solo aplica a archivos: una carpeta no cumple "mayor a 1 MB".
        if (size.minBytes > 0 && (item.isDirectory || item.sizeBytes < size.minBytes)) return false
        return true
    }
}
