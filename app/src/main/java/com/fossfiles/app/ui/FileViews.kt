package com.fossfiles.app.ui

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fossfiles.app.FileItem
import com.fossfiles.app.FileKind
import com.fossfiles.app.FileViewModel
import com.fossfiles.app.ThumbnailLoader
import com.fossfiles.app.openFileExternally
import com.fossfiles.app.formatSize
import com.fossfiles.app.kind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Vistas de archivos compartidas por el Home (recientes), las carpetas y las categorías.
// Sin animaciones: las miniaturas simplemente aparecen cuando están listas.

internal fun iconFor(item: FileItem): ImageVector = when (item.kind()) {
    FileKind.FOLDER -> Icons.Default.Folder
    FileKind.IMAGE -> Icons.Default.Image
    FileKind.VIDEO -> Icons.Default.Movie
    FileKind.AUDIO -> Icons.Default.AudioFile
    FileKind.DOCUMENT -> Icons.Default.Description
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** Miniatura de fotos, videos y portadas de audio; para el resto (o mientras carga) muestra [fallback]. */
@Composable
internal fun FileThumbnail(
    item: FileItem,
    requestSize: Dp,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit
) {
    val kind = item.kind()
    val px = with(LocalDensity.current) { requestSize.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, item.file.absolutePath, item.lastModified, px) {
        value = if (kind == FileKind.IMAGE || kind == FileKind.VIDEO || kind == FileKind.AUDIO) {
            ThumbnailLoader.load(item, px)?.asImageBitmap()
        } else {
            null
        }
    }
    val ready = bitmap
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (ready != null) {
            Image(
                bitmap = ready,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            fallback()
        }
    }
}

@Composable
internal fun rememberDateFormat(): SimpleDateFormat =
    remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }

@Composable
internal fun FileRow(
    item: FileItem,
    selected: Boolean,
    dateFmt: SimpleDateFormat,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    favorite: Boolean = false
) {
    val highlight = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlight)
            .plainClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Seleccionado",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }
        } else {
            FileThumbnail(item, 40.dp, Modifier.size(40.dp).clip(RoundedCornerShape(6.dp))) {
                Icon(iconFor(item), contentDescription = null, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = if (item.isDirectory) dateFmt.format(Date(item.lastModified))
                       else "${formatSize(item.sizeBytes)} · ${dateFmt.format(Date(item.lastModified))}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (favorite) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.Star,
                contentDescription = "Favorito",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun FileGridCell(
    item: FileItem,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    favorite: Boolean = false
) {
    val highlight = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(highlight)
            .plainClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            FileThumbnail(item, 128.dp, Modifier.fillMaxSize()) {
                Icon(iconFor(item), contentDescription = null, modifier = Modifier.size(40.dp))
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Seleccionado",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp)
                )
            }
            if (favorite) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = "Favorito",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp)
                )
            }
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}

/** Lista o cuadrícula de archivos, según [grid]. Tocar abre; con selección activa, tocar alterna. */
@Composable
internal fun FileCollection(
    items: List<FileItem>,
    selection: Set<String>,
    favorites: Set<String>,
    grid: Boolean,
    onOpen: (FileItem) -> Unit,
    onToggle: (FileItem) -> Unit,
    onLongPress: (FileItem) -> Unit
) {
    val selecting = selection.isNotEmpty()
    if (grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            contentPadding = PaddingValues(8.dp)
        ) {
            gridItems(items, key = { it.file.absolutePath }) { item ->
                FileGridCell(
                    item = item,
                    selected = item.file.absolutePath in selection,
                    onClick = { if (selecting) onToggle(item) else onOpen(item) },
                    onLongClick = { onLongPress(item) },
                    favorite = item.file.absolutePath in favorites
                )
            }
        }
    } else {
        val dateFmt = rememberDateFormat()
        LazyColumn {
            items(items, key = { it.file.absolutePath }) { item ->
                FileRow(
                    item = item,
                    selected = item.file.absolutePath in selection,
                    dateFmt = dateFmt,
                    onClick = { if (selecting) onToggle(item) else onOpen(item) },
                    onLongClick = { onLongPress(item) },
                    favorite = item.file.absolutePath in favorites
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * Tocar un elemento: las carpetas se abren en el explorador, las imágenes en el visor propio y los
 * audios/videos en el reproductor propio (con los demás de [source]); el resto, con la app externa
 * que elija el sistema.
 */
internal fun openItem(viewModel: FileViewModel, context: Context, item: FileItem, source: List<FileItem>) {
    when {
        item.isDirectory -> viewModel.openFolder(item.file)
        viewModel.canPreview(item) -> viewModel.openViewer(item, source)
        viewModel.canPlay(item) -> viewModel.openMedia(item, source)
        else -> openFileExternally(context, item.file)?.let { viewModel.message.value = it }
    }
}
