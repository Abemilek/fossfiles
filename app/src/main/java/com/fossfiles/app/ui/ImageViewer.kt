package com.fossfiles.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fossfiles.app.FileItem
import com.fossfiles.app.ThumbnailLoader
import com.fossfiles.app.ViewerState

/**
 * Vista previa rápida de imágenes, dentro de la app. Sin animaciones: el cambio entre imágenes y el
 * zoom son instantáneos (pellizcar para acercar, doble toque para alternar el zoom, botones
 * para pasar a la anterior/siguiente). Carga la imagen reducida a 2048 px para no gastar memoria.
 */
@Composable
fun ImageViewer(
    state: ViewerState,
    onStep: (Int) -> Unit,
    onOpenWith: (FileItem) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val item = state.images[state.index]
    val key = item.file.absolutePath

    var scale by remember(key) { mutableStateOf(1f) }
    var offset by remember(key) { mutableStateOf(Offset.Zero) }
    val bitmap by produceState<ImageBitmap?>(null, key) {
        value = ThumbnailLoader.loadFull(item.file, 2048)?.asImageBitmap()
    }
    val ready = bitmap

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (ready != null) {
            Image(
                bitmap = ready,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(key) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            val maxX = size.width * (newScale - 1f) / 2f
                            val maxY = size.height * (newScale - 1f) / 2f
                            scale = newScale
                            offset = if (newScale == 1f) {
                                Offset.Zero
                            } else {
                                Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY)
                                )
                            }
                        }
                    }
                    .pointerInput(key) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            }
                        )
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    )
            )
        } else {
            Text("Cargando…", color = Color.White, modifier = Modifier.align(Alignment.Center))
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
                    text = "${state.index + 1}/${state.images.size}  ${item.name}",
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconAction(Icons.AutoMirrored.Filled.OpenInNew, "Abrir con otra app") { onOpenWith(item) }
            }
            if (state.images.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconAction(Icons.AutoMirrored.Filled.ArrowBack, "Anterior") { onStep(-1) }
                    IconAction(Icons.AutoMirrored.Filled.ArrowForward, "Siguiente") { onStep(1) }
                }
            }
        }
    }
}
