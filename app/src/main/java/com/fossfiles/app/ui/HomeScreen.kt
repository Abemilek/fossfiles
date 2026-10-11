package com.fossfiles.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.unit.dp
import com.fossfiles.app.Category
import com.fossfiles.app.FileViewModel
import com.fossfiles.app.StorageInfo
import com.fossfiles.app.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: FileViewModel) {
    val storage by viewModel.storageInfo

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("FossFiles") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            StorageCard(storage)

            Spacer(Modifier.height(24.dp))
            Text("Categorías", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(categoryTiles) { tile ->
                    CategoryTile(tile.label, tile.icon) { viewModel.openCategory(tile.category) }
                }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                    CategoryTile("Almacenamiento interno", Icons.Default.Folder, fullWidth = true) {
                        viewModel.openInternalStorage()
                    }
                }
            }
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
private fun StorageCard(storage: StorageInfo) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Almacenamiento", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { storage.usedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "${formatSize(storage.usedBytes)} usados de ${formatSize(storage.totalBytes)}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CategoryTile(label: String, icon: ImageVector, fullWidth: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (!fullWidth) it.height(96.dp) else it }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
