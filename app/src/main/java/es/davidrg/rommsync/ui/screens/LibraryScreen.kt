package es.davidrg.rommsync.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.compose.AsyncImage
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.domain.model.DownloadStatus
import es.davidrg.rommsync.domain.model.Platform
import es.davidrg.rommsync.domain.model.Rom
import es.davidrg.rommsync.domain.model.RomWithStatus
import es.davidrg.rommsync.ui.viewmodel.LibraryEvent
import es.davidrg.rommsync.ui.viewmodel.LibraryViewModel

private enum class RomFilter(val label: String) {
    ALL("Todos"), MISSING("Faltantes"), DOWNLOADED("Descargados")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container
    val configuration = LocalConfiguration.current

    val viewModel: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { LibraryViewModel(container.romRepository, container.downloadManager) }
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

    // ── Advanced filters (genre/region) ────────────────────────────────
    val availableGenres by viewModel.availableGenres.collectAsState()
    val availableRegions by viewModel.availableRegions.collectAsState()
    val currentGenre by viewModel.selectedGenre.collectAsState()
    val currentRegion by viewModel.selectedRegion.collectAsState()
    val hasActiveFilters by viewModel.hasActiveFilters.collectAsState()
    var showFiltersSheet by remember { mutableStateOf(false) }
    // Draft state for the bottom sheet (applied on "Aplicar")
    var draftGenre by remember { mutableStateOf<String?>(null) }
    var draftRegion by remember { mutableStateOf<String?>(null) }

    // ── Server-side search with debounce ──────────────────────────────
    LaunchedEffect(selectedPlatformId) {
        snapshotFlow { searchQuery }
            .debounce(400)
            .distinctUntilChanged()
            .collect { query ->
                if (selectedPlatformId != null) {
                    viewModel.searchRoms(query, settings.serverUrl, settings.apiKey)
                }
            }
    }

    // Bottom sheet state for long-press game details
    var selectedRom by remember { mutableStateOf<Rom?>(null) }
    val sheetState = rememberModalBottomSheetState()

    // Snackbar for download feedback
    val snackbarHostState = remember { SnackbarHostState() }

    // Collect one-shot events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LibraryEvent.DownloadStarted -> {
                    snackbarHostState.showSnackbar(
                        message = "⬇ ${event.romName}",
                        duration = SnackbarDuration.Short,
                    )
                }
                is LibraryEvent.BatchDownloadStarted -> {
                    snackbarHostState.showSnackbar(
                        message = "Encoladas ${event.count} descargas",
                        duration = SnackbarDuration.Short,
                    )
                }
                is LibraryEvent.Error -> {
                    snackbarHostState.showSnackbar(
                        message = "❌ ${event.message}",
                        duration = SnackbarDuration.Long,
                    )
                }
            }
        }
    }

    // Filter ROMs by status only (search is server-side now)
    val filteredRoms by remember(romsWithStatus, selectedFilter) {
        derivedStateOf {
            when (selectedFilter) {
                RomFilter.ALL -> romsWithStatus
                RomFilter.MISSING -> romsWithStatus.filter { it.status == DownloadStatus.NOT_DOWNLOADED }
                RomFilter.DOWNLOADED -> romsWithStatus.filter { it.status == DownloadStatus.DOWNLOADED }
            }
        }
    }

    // Visible NOT_DOWNLOADED ROMs — used by the batch download action.
    val missingRoms by remember(filteredRoms) {
        derivedStateOf { filteredRoms.filter { it.status == DownloadStatus.NOT_DOWNLOADED } }
    }

    // State for the batch download confirmation dialog.
    var showBatchDialog by remember { mutableStateOf(false) }

    // Adaptive grid: more columns in landscape (handhelds 16:9 / 4:3)
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val minCardSize = if (isLandscape) 110.dp else 140.dp

    // ── Infinite scroll state ──────────────────────────────────────────
    val gridState = rememberLazyGridState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()

    // Detect when user scrolls near the bottom → trigger loadMore
    val reachedBottom by remember {
        derivedStateOf {
            val lastVisibleIndex = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = filteredRoms.size
            totalItems > 0 && lastVisibleIndex >= totalItems - 8
        }
    }
    LaunchedEffect(reachedBottom, selectedPlatformId) {
        if (reachedBottom && hasMore && !isLoadingMore && selectedPlatformId != null) {
            viewModel.loadMore(settings.serverUrl, settings.apiKey)
        }
    }

    Scaffold(
        topBar = {
            if (!isLandscape) {
                TopAppBar(
                    title = { Text("Biblioteca") },
                    actions = {
                        // Advanced filters button with badge when active
                        androidx.compose.material3.BadgedBox(
                            badge = {
                                if (hasActiveFilters) {
                                    androidx.compose.material3.Badge(
                                        containerColor = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                            },
                        ) {
                            IconButton(onClick = {
                                draftGenre = currentGenre
                                draftRegion = currentRegion
                                showFiltersSheet = true
                            }) {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = "Filtros avanzados",
                                )
                            }
                        }
                        val showBatchButton = selectedPlatformId != null && missingRoms.isNotEmpty()
                        if (showBatchButton) {
                            IconButton(onClick = { showBatchDialog = true }) {
                                androidx.compose.material3.Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    content = {
                                        Text(
                                            text = "${missingRoms.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onError,
                                        )
                                    },
                                )
                                Icon(
                                    Icons.Filled.DownloadDone,
                                    contentDescription = "Descargar faltantes (${missingRoms.size})",
                                )
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            // In landscape: platform + search + filter chips all compact in one area
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExposedDropdownMenuBox(
                        expanded = platformMenuExpanded,
                        onExpandedChange = { platformMenuExpanded = it },
                        modifier = Modifier.weight(1f),
                    ) {
                        OutlinedTextField(
                            value = platforms.find { it.id == selectedPlatformId }?.name
                                ?: "Plataforma",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(platformMenuExpanded) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded = platformMenuExpanded,
                            onDismissRequest = { platformMenuExpanded = false },
                        ) {
                            platforms.forEach { platform ->
                                DropdownMenuItem(
                                    text = { Text("${platform.name} (${platform.romCount})") },
                                    onClick = {
                                        searchQuery = ""
                                        viewModel.selectPlatform(platform.id, settings.serverUrl, settings.apiKey)
                                        platformMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Buscar...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    // Batch download button in landscape
                    val showBatchButton = selectedPlatformId != null && missingRoms.isNotEmpty()
                    if (showBatchButton) {
                        androidx.compose.material3.FilledTonalButton(
                            onClick = { showBatchDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Icon(
                                Icons.Filled.DownloadDone,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                " ${missingRoms.size}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                // Filter chips compact row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RomFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter.label) },
                        )
                    }
                    // Advanced filters button (landscape)
                    FilterChip(
                        selected = hasActiveFilters,
                        onClick = {
                            draftGenre = currentGenre
                            draftRegion = currentRegion
                            showFiltersSheet = true
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text("Filtros") },
                    )
                }
            } else {
                ExposedDropdownMenuBox(
                    expanded = platformMenuExpanded,
                    onExpandedChange = { platformMenuExpanded = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    OutlinedTextField(
                        value = platforms.find { it.id == selectedPlatformId }?.name
                            ?: "Selecciona plataforma",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Plataforma") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(platformMenuExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                    )
                    ExposedDropdownMenu(
                        expanded = platformMenuExpanded,
                        onDismissRequest = { platformMenuExpanded = false },
                    ) {
                        platforms.forEach { platform ->
                            DropdownMenuItem(
                                text = { Text("${platform.name} (${platform.romCount})") },
                                onClick = {
                                    searchQuery = ""
                                    viewModel.selectPlatform(platform.id, settings.serverUrl, settings.apiKey)
                                    platformMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Buscar en toda la plataforma...") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RomFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter.label) },
                        )
                    }
                    // Advanced filters button (portrait) — also reachable from TopAppBar
                    FilterChip(
                        selected = hasActiveFilters,
                        onClick = {
                            draftGenre = currentGenre
                            draftRegion = currentRegion
                            showFiltersSheet = true
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        label = { Text("Filtros") },
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

            // ── Empty state ─────────────────────────────────────────────
            if (filteredRoms.isEmpty() && !isLoading && !isLoadingMore) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val emptyMessage = when {
                        searchQuery.isNotBlank() -> "No hay juegos que coincidan con la búsqueda"
                        selectedFilter == RomFilter.MISSING -> "Todos los juegos están descargados 🎉"
                        selectedFilter == RomFilter.DOWNLOADED -> "Aún no hay juegos descargados"
                        else -> "No hay juegos para esta plataforma"
                    }
                    Text(
                        emptyMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            PullToRefreshBox(
                isRefreshing = isLoading && romsWithStatus.isNotEmpty(),
                onRefresh = {
                    if (selectedPlatformId != null) {
                        viewModel.refresh(settings.serverUrl, settings.apiKey)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = minCardSize),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(filteredRoms, key = { it.rom.id }) { romStatus ->
                        RomCard(
                            romWithStatus = romStatus,
                            onDownload = {
                                if (settings.isConfigured) {
                                    container.downloadManager.enqueueDownload(
                                        rom = romStatus.rom,
                                        serverUrl = settings.serverUrl,
                                    )
                                    viewModel.onDownloadEnqueued(romStatus.rom.name)
                                }
                            },
                            onLongPress = { selectedRom = romStatus.rom },
                        )
                    }
                    // Loading more footer
                    if (isLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Bottom sheet: game details ─────────────────────────────────────
    if (selectedRom != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedRom = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            RomDetailSheet(
                rom = selectedRom!!,
                isDownloaded = romsWithStatus.any { it.rom.id == selectedRom!!.id && it.status == DownloadStatus.DOWNLOADED },
                isDownloading = romsWithStatus.any { it.rom.id == selectedRom!!.id && it.status == DownloadStatus.DOWNLOADING },
                onDownload = {
                    if (settings.isConfigured) {
                        container.downloadManager.enqueueDownload(
                            rom = selectedRom!!,
                            serverUrl = settings.serverUrl,
                        )
                        viewModel.onDownloadEnqueued(selectedRom!!.name)
                    }
                    selectedRom = null
                },
                onClose = { selectedRom = null },
            )
        }
    }

    // ── Bottom sheet: advanced filters (genre/region) ──────────────────
    if (showFiltersSheet) {
        val filtersSheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showFiltersSheet = false },
            sheetState = filtersSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            AdvancedFiltersSheet(
                genres = availableGenres,
                regions = availableRegions,
                selectedGenre = draftGenre,
                selectedRegion = draftRegion,
                onGenreChange = { genre ->
                    draftGenre = if (draftGenre == genre) null else genre
                },
                onRegionChange = { region ->
                    draftRegion = if (draftRegion == region) null else region
                },
                onClear = {
                    draftGenre = null
                    draftRegion = null
                },
                onApply = {
                    viewModel.applyFilters(
                        serverUrl = settings.serverUrl,
                        apiKey = settings.apiKey,
                        genre = draftGenre,
                        region = draftRegion,
                    )
                    showFiltersSheet = false
                },
            )
        }
    }

    // ── Batch download confirmation dialog ────────────────────────────────
    if (showBatchDialog && missingRoms.isNotEmpty()) {
        val platformName = platforms.find { it.id == selectedPlatformId }?.name ?: "seleccionada"
        AlertDialog(
            onDismissRequest = { showBatchDialog = false },
            icon = {
                Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text("Descargar faltantes") },
            text = {
                Text(
                    "¿Descargar ${missingRoms.size} ROMs faltantes para «$platformName»?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.enqueueBatchDownload(
                            roms = missingRoms.map { it.rom },
                            serverUrl = settings.serverUrl,
                        )
                        showBatchDialog = false
                    },
                ) {
                    Text("Descargar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDialog = false }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

// ── Game card with long-press ──────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RomCard(
    romWithStatus: RomWithStatus,
    onDownload: (RomWithStatus) -> Unit,
    onLongPress: (RomWithStatus) -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = {},
                onLongClick = { onLongPress(romWithStatus) },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.72f)
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
                        .padding(6.dp)
                        .size(20.dp),
                )
            }

            // Download button overlay (only for not-downloaded)
            if (romWithStatus.status == DownloadStatus.NOT_DOWNLOADED) {
                IconButton(
                    onClick = { onDownload(romWithStatus) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                            RoundedCornerShape(6.dp),
                        ),
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Descargar",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Downloading spinner overlay
            if (romWithStatus.status == DownloadStatus.DOWNLOADING) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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

// ── Bottom sheet: game detail ──────────────────────────────────────────
@Composable
private fun RomDetailSheet(
    rom: Rom,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    onClose: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
    ) {
        // Cover + title row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AsyncImage(
                model = rom.coverUrlLarge ?: rom.coverUrlSmall,
                contentDescription = rom.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 100.dp, height = 140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = rom.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = rom.platformSlug.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                val metaParts = buildList {
                    if (rom.regions.isNotEmpty()) addAll(rom.regions)
                    rom.revision?.takeIf { it.isNotBlank() }?.let { add("Rev $it") }
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Cerrar")
            }
        }

        // Summary with expandable text
        rom.summary?.takeIf { it.isNotBlank() }?.let { summary ->
            Spacer(modifier = Modifier.size(12.dp))
            val maxLines = if (expanded) Int.MAX_VALUE else 3
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Show "ver más/menos" button only if text is long enough
            if (summary.length > 120) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.padding(top = 0.dp),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        if (expanded) " Ver menos" else " Ver más",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.size(16.dp))

        // Metadata rows
        DetailRow("Archivo", rom.fileName)
        DetailRow("Tamaño", formatFileSize(rom.fileSizeBytes))
        rom.fileNameNoTags?.let { DetailRow("Nombre limpio", it) }
        rom.fileExtension?.let { DetailRow("Extensión", it) }
        if (rom.languages.isNotEmpty()) DetailRow("Idiomas", rom.languages.joinToString(", "))
        if (rom.genres.isNotEmpty()) DetailRow("Géneros", rom.genres.joinToString(", "))
        if (rom.isMulti) DetailRow("Multi-archivo", "Sí (${rom.files.size} archivos)")
        rom.igdbId?.let { DetailRow("IGDB ID", it.toString()) }

        Spacer(modifier = Modifier.size(20.dp))

        // Action button
        when {
            isDownloaded -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.DownloadDone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        "  Descargado",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            isDownloading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "  Descargando...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                androidx.compose.material3.Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Descargar")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) "${bytes}B" else String.format("%.1f %s", size, units[unitIndex])
}

// ── Advanced filters bottom sheet ──────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedFiltersSheet(
    genres: List<String>,
    regions: List<String>,
    selectedGenre: String?,
    selectedRegion: String?,
    onGenreChange: (String) -> Unit,
    onRegionChange: (String) -> Unit,
    onClear: () -> Unit,
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Filtros avanzados",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Icon(
                Icons.Filled.FilterList,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }

        Spacer(modifier = Modifier.size(16.dp))

        // ── Genre chips ─────────────────────────────────────────────────
        Text(
            text = "Género",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.size(6.dp))
        if (genres.isEmpty()) {
            Text(
                text = "Carga una plataforma para ver géneros disponibles",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                genres.forEach { genre ->
                    FilterChip(
                        selected = selectedGenre == genre,
                        onClick = { onGenreChange(genre) },
                        label = { Text(genre) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.size(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.size(16.dp))

        // ── Region chips ────────────────────────────────────────────────
        Text(
            text = "Región",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.size(6.dp))
        if (regions.isEmpty()) {
            Text(
                text = "Carga una plataforma para ver regiones disponibles",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                regions.forEach { region ->
                    FilterChip(
                        selected = selectedRegion == region,
                        onClick = { onRegionChange(region) },
                        label = { Text(region) },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.size(24.dp))

        // ── Action buttons ──────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f),
            ) {
                Text("Limpiar filtros")
            }
            Button(
                onClick = onApply,
                modifier = Modifier.weight(1f),
            ) {
                Text("Aplicar")
            }
        }
    }
}
