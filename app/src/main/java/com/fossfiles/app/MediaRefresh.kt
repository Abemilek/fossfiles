package com.fossfiles.app

import android.content.Context
import android.media.MediaScannerConnection

/**
 * Avisa al índice multimedia de Android (el que usan la Galería y los reproductores) de que ciertos
 * archivos se crearon, movieron o borraron. En Android 11+ el sistema ya lo detecta solo; en Android
 * 8-10 sin este aviso la Galería no vería lo copiado hasta el siguiente escaneo completo.
 */
object MediaRefresh {
    fun scan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        try {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        } catch (e: Exception) {
            // No es crítico: como mucho el índice se pondrá al día más tarde.
        }
    }
}
