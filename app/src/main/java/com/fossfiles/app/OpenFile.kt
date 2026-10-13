package com.fossfiles.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

fun mimeTypeOf(file: File): String? =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())

private fun uriFor(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

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
    val uri: Uri = try {
        uriFor(context, file)
    } catch (e: Exception) {
        return "No se pudo abrir este archivo"
    }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeTypeOf(file) ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "No hay ninguna app instalada que pueda abrir esto"
    }
}

/**
 * Comparte archivos con otras apps (el selector de compartir es del sistema). Las carpetas
 * no se pueden compartir: hay que comprimirlas antes. Devuelve un mensaje de error o null.
 */
fun shareFiles(context: Context, files: List<File>): String? {
    if (files.isEmpty()) return null
    if (files.any { it.isDirectory }) return "Las carpetas no se pueden compartir: comprímelas primero"

    val uris = try {
        ArrayList(files.map { uriFor(context, it) })
    } catch (e: Exception) {
        return "No se pudo compartir este archivo"
    }

    val intent = if (files.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeOf(files.first()) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        val types = files.map { mimeTypeOf(it) ?: "*/*" }.toSet()
        val common = when {
            types.size == 1 -> types.first()
            types.map { it.substringBefore('/') }.toSet().size == 1 -> types.first().substringBefore('/') + "/*"
            else -> "*/*"
        }
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = common
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    return try {
        context.startActivity(Intent.createChooser(intent, "Compartir"))
        null
    } catch (e: ActivityNotFoundException) {
        "No hay ninguna app para compartir"
    }
}
