package es.davidrg.rommsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.domain.model.DownloadStatus
import es.davidrg.rommsync.domain.model.Platform
import es.davidrg.rommsync.domain.model.RomWithStatus
import es.davidrg.rommsync.ui.viewmodel.LibraryViewModel

private enum class RomFilter(val label: String) {
    ALL("Todos"), MISSING("Faltantes"), DOWNLOADED("Descargados")
}

@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container

    val viewModel: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { LibraryViewModel(container.romRepository) }
        }
    )

    val settings by container.settingsRepository.settings.collectAsState(
        initial = es.davidrg.rommsync.data.local.ServerConfig("", "", "", 2)
    )
    val platforms by container.romRepository.getVisiblePlatforms().collectAsState(initial = emptyList())
    val romsWithStatus by viewModel.romsWithStatus.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val selectedPlatformId by viewModel.selectedPlatformId.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(RomFilter.ALL) }
    var platformMenuExpanded by remember { mutableStateOf(false) }

    // Filter ROMs by search + status
    val filteredRoms = romsWithStatus.filter { rws ->
        val matchesSearch = searchQuery.isBlank() ||
            rws.rom.name.contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (selectedFilter) {
            RomFilter.ALL -> true
            RomFilter.MISSING -> rws.status == DownloadStatus.NOT_DOWNLOADED
            RomFilter.DOWNLOADED -> rws.status == DownloadStatus.DOWNLOADED
        }
        matchesSearch && matchesFilter
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Biblioteca") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // ── Platform selector ───────────────────────────────────────
            ExposedDropdownMenuBox(
                expanded = platformMenuExpanded,
                onExpandedChange = { platformMenuExpanded = it },
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = platforms.find { it.id == selectedPlatformId }?.name ?: "Selecciona plataforma",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Plataforma") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(platformMenuExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = platformMenuExpanded, onDismissRequest = { platformMenuExpanded = false }) {
                    platforms.forEach { platform ->
                        DropdownMenuItem(
                            text = { Text("${platform.name} (${platform.romCount})") },
                            onClick = {
                                viewModel.selectPlatform(platform.id, settings.serverUrl, settings.apiKey)
                                platformMenuExpanded = false
                            },
                        )
                    }
                }
            }

            // ── Search bar ──────────────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar juego...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )

            // ── Filter chips ────────────────────────────────────────────
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RomFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(filter.label) },
                    )
                }
            }

            // ── Grid of games ───────────────────────────────────────────
            if (isLoading && romsWithStatus.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                return@Column
            }

            if (selectedPlatformId == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Selecciona una plataforma para ver los juegos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            ) {
                items(filteredRoms, key = { it.rom.id }) { romStatus ->
                    RomCard(
                        romWithStatus = romStatus,
                        onDownload = {
                            if (settings.isConfigured) {
                                container.downloadManager.enqueueDownload(
                                    rom = it.rom,
                                    serverUrl = settings.serverUrl,
                                    apiKey = settings.apiKey,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RomCard(
    romWithStatus: RomWithStatus,
    onDownload: (RomWithStatus) -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = romWithStatus.rom.coverUrlLarge ?: romWithStatus.rom.coverUrlSmall,
                contentDescription = romWithStatus.rom.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            // Downloaded badge
            if (romWithStatus.status == DownloadStatus.DOWNLOADED) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Descargado",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }

            // Download button
            if (romWithStatus.status == DownloadStatus.NOT_DOWNLOADED) {
                IconButton(
                    onClick = { onDownload(romWithStatus) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                            RoundedCornerShape(4.dp),
                        ),
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Descargar",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }

            if (romWithStatus.status == DownloadStatus.DOWNLOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    strokeWidth = 2.dp,
                )
            }
        }

        Text(
            text = romWithStatus.rom.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
