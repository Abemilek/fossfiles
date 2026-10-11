package com.fossfiles.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Abre un archivo con la app externa que el usuario elija. A propósito NO usa
 * Intent.createChooser: eso siempre fuerza el selector temporal y esconde el
 * botón "Siempre". Llamando startActivity directo, es Android quien decide
 * mostrar su propio diálogo de resolución con "Solo una vez" / "Siempre" (o
 * abrir directo si el usuario ya fijó una app por defecto antes).
 *
 * Devuelve un mensaje de error para mostrar, o null si todo salió bien.
 */
fun openFileExternally(context: Context, file: File): String? {
    val authority = "${context.packageName}.fileprovider"
    val uri: Uri = try {
        FileProvider.getUriForFile(context, authority, file)
    } catch (e: Exception) {
        return "No se pudo abrir este archivo"
    }

    val mimeType = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase())
        ?: "*/*"

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "No hay ninguna app instalada que pueda abrir esto"
    }
}
