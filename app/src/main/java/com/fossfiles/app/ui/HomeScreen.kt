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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fossfiles.app.Category
import com.fossfiles.app.FileViewModel
import com.fossfiles.app.VolumeUsage
import com.fossfiles.app.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: FileViewModel) {
    val context = LocalContext.current
    val volumes by viewModel.volumes
    val quickLinks by viewModel.quickLinks
    val recent by viewModel.recent
    val favorites by viewModel.favorites
    val dateFmt = rememberDateFormat()
    var query by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FossFiles") },
                actions = { IconAction(Icons.Default.Settings, "Ajustes") { viewModel.openSettings() } }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                PlainTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Buscar en todo el almacenamiento...",
                    leadingIcon = Icons.Default.Search,
                    onClear = { query = "" },
                    onDone = { viewModel.searchFromHome(query) },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                )
            }

            // Un cuadro por volumen (almacenamiento interno y, si hay, tarjeta SD); tocarlo lo abre.
            items(volumes, key = { it.volume.root.path }) { volume ->
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    StorageCard(volume) { viewModel.openVolume(volume.volume) }
                }
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
            items(categoryRows) { pair ->
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
                    CategoryTile("Archivos grandes", Icons.Default.Storage, Modifier.fillMaxWidth()) {
                        viewModel.openLargeFiles()
                    }
                }
            }
            item {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    CategoryTile("Papelera", Icons.Default.Delete, Modifier.fillMaxWidth()) {
                        viewModel.openTrash()
                    }
                }
            }

            if (recent.isNotEmpty()) {
                item { SectionTitle("Recientes") }
                items(recent, key = { it.path }) { item ->
                    FileRow(
                        item = item,
                        selected = false,
                        dateFmt = dateFmt,
                        onClick = { openItem(viewModel, context, item, recent) },
                        onLongClick = {},
                        favorite = item.path in favorites
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
    CategoryTileInfo(Category.DOCUMENTS, "Documentos", Icons.Default.Description),
    CategoryTileInfo(Category.FAVORITES, "Favoritos", Icons.Default.Star)
)

// Se agrupan una sola vez (no en cada recomposición del Home).
private val categoryRows = categoryTiles.chunked(2)

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
private fun StorageCard(item: VolumeUsage, onClick: () -> Unit) {
    val storage = item.usage
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .plainClickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(item.volume.label, style = MaterialTheme.typography.titleMedium)
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
