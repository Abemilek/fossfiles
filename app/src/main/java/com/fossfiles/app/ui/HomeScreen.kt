package com.fossfiles.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fossfiles.app.Category
import com.fossfiles.app.FileViewModel
import com.fossfiles.app.StorageInfo
import com.fossfiles.app.formatSize
import com.fossfiles.app.openFileExternally

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: FileViewModel) {
    val context = LocalContext.current
    val storage by viewModel.storageInfo
    val quickLinks by viewModel.quickLinks
    val recent by viewModel.recent
    val dateFmt = rememberDateFormat()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("FossFiles") })
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Box(Modifier.padding(16.dp)) { StorageCard(storage) }
            }

            if (quickLinks.isNotEmpty()) {
                item { SectionTitle("Acceso rápido") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(quickLinks) { link ->
                            QuickChip(link.label) { viewModel.openQuickLink(link) }
                        }
                    }
                }
            }

            item { SectionTitle("Categorías") }
            // Dos por fila, dibujadas a mano (una cuadrícula perezosa no puede ir dentro de otra lista).
            items(categoryTiles.chunked(2)) { pair ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pair.forEach { tile ->
                        CategoryTile(tile.label, tile.icon, Modifier.weight(1f)) { viewModel.openCategory(tile.category) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    CategoryTile("Almacenamiento interno", Icons.Default.Folder, Modifier.fillMaxWidth()) {
                        viewModel.openInternalStorage()
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item { SectionTitle("Recientes") }
                items(recent, key = { it.file.absolutePath }) { item ->
                    FileRow(
                        item = item,
                        selected = false,
                        dateFmt = dateFmt,
                        onClick = { openFileExternally(context, item.file)?.let { viewModel.message.value = it } },
                        onLongClick = {}
                    )
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

private data class CategoryTileInfo(val category: Category, val label: String, val icon: ImageVector)

private val categoryTiles = listOf(
    CategoryTileInfo(Category.IMAGES, "Fotos", Icons.Default.Image),
    CategoryTileInfo(Category.VIDEOS, "Videos", Icons.Default.Movie),
    CategoryTileInfo(Category.AUDIO, "Audio", Icons.Default.AudioFile),
    CategoryTileInfo(Category.DOWNLOADS, "Descargas", Icons.Default.Download),
    CategoryTileInfo(Category.DOCUMENTS, "Documentos", Icons.Default.Description)
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
    )
}

@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .plainClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
private fun StorageCard(storage: StorageInfo) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text("Almacenamiento", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        // Barra dibujada a mano: LinearProgressIndicator anima su avance.
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(storage.usedFraction)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${formatSize(storage.usedBytes)} usados de ${formatSize(storage.totalBytes)}",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CategoryTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .plainClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
    }
}
