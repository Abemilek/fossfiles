package com.fossfiles.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
 * Miniaturas de fotos, videos y portadas de audio, y carga de imágenes para el visor, usando solo
 * APIs del sistema (cero librerías de terceros, cero peso extra en el APK). Se generan bajo demanda,
 * solo para lo que está visible, con un máximo de 3 a la vez y una caché en memoria acotada
 * (1/16 del heap de la app).
 */
object ThumbnailLoader {

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 16).toInt().coerceAtLeast(2 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val gate = Semaphore(3)

    /** Libera la caché de miniaturas (al faltar memoria, al pasar a segundo plano o desde Ajustes). */
    fun clear() {
        cache.evictAll()
    }

    /** Devuelve la miniatura o null si no se puede (formato raro, sin portada, archivo dañado). */
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

    /** Imagen reducida a como mucho [maxPx] por lado y con la orientación EXIF aplicada (para el visor). */
    suspend fun loadFull(file: File, maxPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            decodeFull(file, maxPx)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun create(file: File, kind: FileKind, px: Int): Bitmap? = when (kind) {
        FileKind.IMAGE ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createImageThumbnail(file, Size(px, px), null)
            else decodeSampled(file, px)
        FileKind.VIDEO ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ThumbnailUtils.createVideoThumbnail(file, Size(px, px), null)
            else videoFrame(file, px)
        FileKind.AUDIO -> audioArt(file, px)
        else -> null
    }

    private fun sampleSizeFor(bounds: BitmapFactory.Options, target: Int): Int {
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        return sample
    }

    // Android 8-9: decodifica la imagen ya reducida (inSampleSize) para no cargarla entera.
    private fun decodeSampled(file: File, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, px) })
    }

    private fun videoFrame(file: File, px: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val frame = retriever.getFrameAtTime(0) ?: return null
            val scale = px.toFloat() / minOf(frame.width, frame.height)
            return if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                frame
            }
        } finally {
            retriever.release()
        }
    }

    // Portada incrustada en el archivo de audio (la mayoría de los MP3/M4A la traen).
    private fun audioArt(file: File, px: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            val bytes = retriever.embeddedPicture ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            return BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds, px) }
            )
        } finally {
            retriever.release()
        }
    }

    private fun decodeFull(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxPx) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return applyExifOrientation(bitmap, file)
    }

    // BitmapFactory ignora la orientación EXIF: sin esto, muchas fotos de cámara saldrían de lado.
    private fun applyExifOrientation(bitmap: Bitmap, file: File): Bitmap {
        val orientation = try {
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: OutOfMemoryError) {
            bitmap
        }
    }
}
