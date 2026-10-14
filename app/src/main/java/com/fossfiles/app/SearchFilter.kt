package com.fossfiles.app

enum class TypeFilter(val label: String) {
    ALL("Todo"), FOLDERS("Carpetas"), IMAGES("Imágenes"), VIDEOS("Videos"), AUDIO("Audio"), DOCUMENTS("Documentos")
}

enum class DateFilter(val label: String, val days: Int?) {
    ANY("Cualquiera", null), DAY("Últimas 24 h", 1), WEEK("7 días", 7), MONTH("30 días", 30), YEAR("1 año", 365)
}

private const val MB = 1024L * 1024L

enum class SizeFilter(val label: String, val minBytes: Long) {
    ANY("Cualquiera", 0L), OVER_1MB("> 1 MB", MB), OVER_10MB("> 10 MB", 10 * MB),
    OVER_100MB("> 100 MB", 100 * MB), OVER_1GB("> 1 GB", 1024 * MB)
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
