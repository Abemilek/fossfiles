package com.fossfiles.app

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Consulta el índice MediaStore que Android ya mantiene actualizado en segundo
 * plano, en vez de recorrer el disco cada vez. Es la misma fuente de datos que
 * usa la app de Google, así que la velocidad de listar Fotos/Videos/Audio
 * queda equiparada, sin necesitar ningún SDK de terceros.
 */
object MediaStoreScanner {

    fun images(context: Context): List<FileItem> = query(
        context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    )

    fun videos(context: Context): List<FileItem> = query(
        context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    )

    fun audio(context: Context): List<FileItem> = query(
        context, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    )

    fun downloads(context: Context): List<FileItem> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            query(context, MediaStore.Downloads.EXTERNAL_CONTENT_URI)
        } else {
            emptyList() // en versiones viejas se resuelve con el escaneo de carpeta normal
        }
    }

    // MediaStore.MediaColumns.DATA está deprecado, pero es la vía para obtener rutas
    // reales de archivo (la app tiene acceso total, no usa SAF).
    @Suppress("DEPRECATION")
    private fun query(context: Context, uri: android.net.Uri): List<FileItem> {
        val results = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        try {
            context.contentResolver.query(
                uri, projection, null, null,
                "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: continue
                    val file = File(path)
                    // El índice puede ir un poco por detrás del disco: se descarta lo que ya no existe.
                    if (!file.exists()) continue
                    // Tamaño y fecha vienen del índice: evita 2 llamadas al sistema por archivo.
                    results.add(
                        FileItem(
                            file = file,
                            name = file.name,
                            isDirectory = false,
                            sizeBytes = cursor.getLong(sizeCol),
                            lastModified = cursor.getLong(dateCol) * 1000L // MediaStore usa segundos
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Si el índice falla por lo que sea, mejor una lista vacía que tronar la app.
        }
        return results
    }
}
