package com.fossfiles.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.view.Surface
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * Reproductor de audio y video con el MediaPlayer del sistema (cero librerías, cero peso extra).
 * Pide el foco de audio (se pausa si otra app lo toma) y se pausa al salir de la app: no hay servicio
 * en segundo plano. Todo ocurre en el hilo principal.
 */
class PlayerController(context: Context, private val onCompleted: () -> Unit) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false

    val isPlaying = mutableStateOf(false)
    val positionMs = mutableStateOf(0)
    val durationMs = mutableStateOf(0)
    val videoAspect = mutableStateOf<Float?>(null)
    val error = mutableStateOf<String?>(null)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()

    /** Carga [file] y empieza a reproducirlo en cuanto está listo. */
    fun load(file: File) {
        releasePlayer()
        error.value = null
        positionMs.value = 0
        durationMs.value = 0
        videoAspect.value = null
        isPlaying.value = false

        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(file.path)
            mp.setOnPreparedListener { p ->
                if (player === p) {
                    prepared = true
                    durationMs.value = p.duration
                    play()
                }
            }
            mp.setOnVideoSizeChangedListener { _, width, height ->
                if (width > 0 && height > 0) videoAspect.value = width.toFloat() / height
            }
            mp.setOnCompletionListener { p ->
                if (player === p) {
                    isPlaying.value = false
                    positionMs.value = durationMs.value
                    audioManager.abandonAudioFocusRequest(focusRequest)
                    onCompleted()
                }
            }
            mp.setOnErrorListener { p, _, _ ->
                if (player === p) fail()
                true
            }
            surface?.let { mp.setSurface(it) }
            mp.prepareAsync()
        } catch (e: Exception) {
            fail()
        }
    }

    fun play() {
        val p = player ?: return
        if (!prepared) return
        if (audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_FAILED) return
        try {
            p.start()
            isPlaying.value = true
        } catch (e: IllegalStateException) {
            fail()
        }
    }

    fun pause() {
        val p = player ?: return
        if (!prepared) return
        try {
            if (p.isPlaying) p.pause()
        } catch (e: IllegalStateException) {
            // el reproductor ya no está en un estado válido: se ignora
        }
        isPlaying.value = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun togglePlay() {
        if (isPlaying.value) pause() else play()
    }

    fun seekTo(ms: Int) {
        val p = player ?: return
        if (!prepared) return
        val target = ms.coerceIn(0, durationMs.value)
        try {
            p.seekTo(target)
            positionMs.value = target
        } catch (e: IllegalStateException) {
            // se ignora
        }
    }

    /** Lo llama la pantalla cada medio segundo mientras está abierta (no es una animación). */
    fun refresh() {
        val p = player ?: return
        if (!prepared) return
        try {
            positionMs.value = p.currentPosition
            isPlaying.value = p.isPlaying
        } catch (e: IllegalStateException) {
            // se ignora
        }
    }

    fun attachSurface(newSurface: Surface) {
        surface?.release()
        surface = newSurface
        try { player?.setSurface(newSurface) } catch (e: IllegalStateException) { /* se ignora */ }
    }

    fun detachSurface() {
        try { player?.setSurface(null) } catch (e: IllegalStateException) { /* se ignora */ }
        surface?.release()
        surface = null
    }

    /** Libera todo (al cerrar el reproductor o salir de la app). */
    fun release() {
        releasePlayer()
        isPlaying.value = false
        positionMs.value = 0
        durationMs.value = 0
        videoAspect.value = null
    }

    private fun fail() {
        error.value = "No se pudo reproducir este archivo"
        isPlaying.value = false
    }

    private fun releasePlayer() {
        val p = player
        player = null
        prepared = false
        if (p != null) {
            try { p.release() } catch (e: Exception) { /* se ignora */ }
        }
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
