package com.fossfiles.app

import java.io.File

/** Utilidades de rutas de almacenamiento sin depender de Android (se prueban en la JVM). */
object VolumePaths {

    /** "/storage/ABCD-1234/Android/data/app/files" -> "/storage/ABCD-1234" (o null si no es una carpeta de app). */
    fun rootFromAppDir(appDir: File): File? {
        val path = appDir.absolutePath
        val idx = path.indexOf("/Android/")
        return if (idx > 0) File(path.substring(0, idx)) else null
    }

    /** El volumen (de [roots]) que contiene [file]; si hay varios anidados, el más específico. */
    fun volumeOf(file: File, roots: List<File>): File? {
        val path = file.path
        return roots
            .filter { path == it.path || path.startsWith(it.path + File.separator) }
            .maxByOrNull { it.path.length }
    }
}
