package com.fossfiles.app.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fossfiles.app.FileItem
import com.fossfiles.app.FileKind
import com.fossfiles.app.MediaState
import com.fossfiles.app.PlayerController
import com.fossfiles.app.kind
import kotlinx.coroutines.delay

/**
 * Reproductor de audio y video integrado. Controles propios y fijos (sin ocultarse con animación):
 * anterior / pausa / siguiente y una barra para saltar a cualquier punto. Al terminar una pista pasa
 * sola a la siguiente. El video usa un TextureView (así los controles se dibujan encima sin problemas).
 */
@Composable
fun MediaPlayerOverlay(
    state: MediaState,
    controller: PlayerController,
    onStep: (Int) -> Unit,
    onOpenWith: (FileItem) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val item = state.items[state.index]
    val isVideo = item.kind() == FileKind.VIDEO
    val playing by controller.isPlaying
    val position by controller.positionMs
    val duration by controller.durationMs
    val aspect by controller.videoAspect
    val error by controller.error

    // Refresca la posición cada medio segundo, solo mientras esta pantalla está abierta.
    LaunchedEffect(controller) {
        while (true) {
            controller.refresh()
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (isVideo) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { context ->
                        TextureView(context).apply {
                            keepScreenOn = true // la pantalla no se apaga mientras se ve un video
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                    controller.attachSurface(Surface(texture))
                                }

                                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                    controller.detachSurface()
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier.aspectRatio(aspect ?: (16f / 9f))
                )
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FileThumbnail(
                    item = item,
                    requestSize = 256.dp,
                    modifier = Modifier
                        .size(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF121212))
                ) {
                    Icon(Icons.Default.AudioFile, contentDescription = null, tint = Color.White, modifier = Modifier.size(96.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    text = item.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }

        // Los íconos de la app son oscuros en tema claro: sobre fondo negro se fuerzan a blanco.
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(Icons.Default.Close, "Cerrar", onClose)
                Text(
                    text = "${state.index + 1}/${state.items.size}  ${item.name}",
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconAction(Icons.AutoMirrored.Filled.OpenInNew, "Abrir con otra app") { onOpenWith(item) }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SeekBar(
                    fraction = if (duration > 0) position.toFloat() / duration else 0f,
                    onSeek = { controller.seekTo((it * duration).toInt()) }
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${formatTime(position)} / ${formatTime(duration)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.weight(1f))
                    IconAction(Icons.Default.SkipPrevious, "Anterior") { onStep(-1) }
                    IconAction(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pausar" else "Reproducir"
                    ) { controller.togglePlay() }
                    IconAction(Icons.Default.SkipNext, "Siguiente") { onStep(1) }
                }
            }
        }
    }
}

/** Barra de progreso dibujada a mano: tocar o arrastrar salta a ese punto. Sin animaciones. */
@Composable
private fun SeekBar(fraction: Float, onSeek: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f)))
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

private fun formatTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
