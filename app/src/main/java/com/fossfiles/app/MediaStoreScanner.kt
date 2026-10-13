package com.fossfiles.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    /** Los últimos archivos modificados de cualquier tipo (sin ocultos ni datos de otras apps). */
    fun recent(context: Context, limit: Int = 12): List<FileItem> {
        val data = MediaStore.MediaColumns.DATA
        val selection = "${MediaStore.MediaColumns.MIME_TYPE} IS NOT NULL" +
            " AND $data NOT LIKE '%/.%'" +
            " AND $data NOT LIKE '%/Android/%'"
        return query(context, MediaStore.Files.getContentUri("external"), selection, limit)
    }

    private fun query(
        context: Context,
        uri: Uri,
        selection: String? = null,
        limit: Int? = null
    ): List<FileItem> {
        val results = mutableListOf<FileItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
            if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        try {
            context.contentResolver.query(uri, projection, args, null)?.use { cursor ->
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataCol) ?: continue
                    val file = File(path)
                    // El índice puede ir un poco por detrás del disco: se descarta lo que ya no existe.
                    if (!file.isFile) continue
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
