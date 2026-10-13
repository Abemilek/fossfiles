package com.fossfiles.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Miniaturas de fotos y videos usando solo APIs del sistema (cero librerías de terceros,
 * cero peso extra en el APK). Se generan bajo demanda, solo para lo que está visible, con
 * un máximo de 3 a la vez y una caché en memoria acotada (1/16 del heap de la app).
 */
object ThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(2 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val gate = Semaphore(3)

    /** Devuelve la miniatura o null si no se puede (formato raro, archivo dañado, etc.). */
    suspend fun load(item: FileItem, sizePx: Int): Bitmap? {
        val key = "${item.file.absolutePath}|${item.lastModified}|$sizePx"
        val cached = cache.get(key)
        if (cached != null) return cached
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                // Si el usuario ya se fue de esta fila mientras esperaba turno, no se gasta trabajo.
                if (!isActive) return@withContext null
                val bitmap = try {
                    create(item.file, item.kind(), sizePx)
                } catch (e: Exception) {
                    null
                } catch (e: OutOfMemoryError) {
                    null
                }
                if (bitmap != null) cache.put(key, bitmap)
                bitmap
            }
        }
    }

    private fun create(file: File, kind: FileKind, px: Int): Bitmap? = when (kind) {
        FileKind.IMAGE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createImageThumbnail(file, Size(px, px), null)
            else decodeSampled(file, px)
        FileKind.VIDEO ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createVideoThumbnail(file, Size(px, px), null)
            else videoFrame(file, px)
        else -> null
    }

    // Android 8-9: decodifica la imagen ya reducida (inSampleSize) para no cargarla entera.
    private fun decodeSampled(file: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun videoFrame(file: File, px: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val frame = retriever.getFrameAtTime(0) ?: return null
            val scale = px.toFloat() / minOf(frame.width, frame.height)
            return if (scale < 1f) {
                Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                frame
            }
        } finally {
            retriever.release()
        }
    }
}
