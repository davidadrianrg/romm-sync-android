package es.davidrg.rommsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.domain.model.Platform
import es.davidrg.rommsync.ui.viewmodel.PlatformsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformsScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container

    val viewModel: PlatformsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { PlatformsViewModel(container.romRepository) }
        }
    )

    val platforms by viewModel.platforms.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val libraryStats by viewModel.libraryStats.collectAsState()
    val settings by container.settingsRepository.settings.collectAsState(
        initial = es.davidrg.rommsync.data.local.ServerConfig("", "", "", 2)
    )

    val retroArchBasePath by container.settingsRepository.retroArchBasePath.collectAsState(
        initial = es.davidrg.rommsync.data.local.SettingsDataStore.DEFAULT_RETROARCH_PATH,
    )

    val allVisible = platforms.isNotEmpty() && platforms.all { it.visible }
    val windowInfo = es.davidrg.rommsync.ui.components.rememberWindowInfo()
    val compact = windowInfo.isCompact

    // La configuración de sync por plataforma solo tiene sentido si la
    // función está activada en Configuración.
    val saveSyncEnabled by container.settingsRepository.saveSyncEnabled.collectAsState(initial = true)
    val esdeExportEnabled by container.settingsRepository.esdeExportEnabled.collectAsState(initial = true)
    val retroHraiExportEnabled by container.settingsRepository.retroHraiExportEnabled.collectAsState(initial = true)

    LaunchedEffect(settings.isConfigured) {
        if (settings.isConfigured && platforms.isEmpty()) {
            viewModel.refreshPlatforms(settings.serverUrl, settings.apiKey)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plataformas") },
                modifier = Modifier.height(if (compact) 48.dp else 64.dp),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    if (platforms.isNotEmpty()) {
                        TextButton(
                            onClick = { viewModel.setAllVisible(!allVisible) },
                            contentPadding = PaddingValues(horizontal = 10.dp),
                        ) {
                            Icon(
                                if (allVisible) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(if (allVisible) "Ninguna" else "Todas")
                        }
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            if (!settings.isConfigured) {
                es.davidrg.rommsync.ui.components.EmptyState(
                    icon = Icons.Outlined.Storage,
                    title = "Sin servidor configurado",
                    description = "Configura el servidor en la pestaña Configuración para ver tus plataformas.",
                )
                return@Column
            }

            // Header card: resumen + actualizar
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "${platforms.count { it.visible }} de ${platforms.size}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "plataformas visibles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(
                        onClick = { viewModel.refreshPlatforms(settings.serverUrl, settings.apiKey) },
                        enabled = !isLoading,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Actualizar")
                    }
                }
            }

            // Estadísticas locales: ROMs descargados y espacio por plataforma
            @Suppress("UNUSED_EXPRESSION")
            libraryStats?.let { stats ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                "${stats.totalRoms} ROMs locales",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                formatStatsBytes(stats.totalBytes),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // Top 5 plataformas por tamaño
                        stats.byPlatform.take(5).forEach { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    p.platformSlug,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${p.romCount} · ${formatStatsBytes(p.totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (stats.byPlatform.size > 5) {
                            Text(
                                "+ ${stats.byPlatform.size - 5} plataformas más",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
            ) {
                items(platforms, key = { it.id }) { platform ->
                    PlatformCard(
                        platform = platform,
                        retroArchBasePath = retroArchBasePath,
                        container = container,
                        syncEnabled = saveSyncEnabled,
                        esdeEnabled = esdeExportEnabled,
                        retroHraiEnabled = retroHraiExportEnabled,
                        onToggle = { viewModel.togglePlatformVisibility(it) },
                        onEmulatorChange = { id, emu -> viewModel.updatePlatformEmulator(id, emu) },
                        onSavesPathChange = { id, path -> viewModel.updatePlatformSavesPath(id, path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlatformCard(
    platform: Platform,
    retroArchBasePath: String,
    container: es.davidrg.rommsync.data.AppContainer,
    syncEnabled: Boolean,
    esdeEnabled: Boolean,
    retroHraiEnabled: Boolean,
    onToggle: (Platform) -> Unit,
    onEmulatorChange: (Int, String?) -> Unit,
    onSavesPathChange: (Int, String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val availableEmulators = remember(platform.slug) {
        es.davidrg.rommsync.data.sync.platform.SaveHandlerRegistry.getAvailableEmulators(platform.slug)
    }
    val defaultEmulator = remember(platform.slug) {
        es.davidrg.rommsync.data.sync.platform.SaveHandlerRegistry.getDefaultEmulator(platform.slug)
    }
    val currentEmulator = platform.emulatorId ?: defaultEmulator.id
    val defaultSavesPath = remember(currentEmulator, platform.slug, retroArchBasePath) {
        es.davidrg.rommsync.data.sync.platform.SaveHandlerRegistry.getDefaultSavesPath(
            emulatorId = currentEmulator,
            platformSlug = platform.slug,
            retroArchBase = retroArchBasePath,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (platform.visible) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                if (platform.visible) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = platform.slug.take(2).uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (platform.visible) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.size(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            platform.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${platform.slug} • ${platform.romCount} ROMs • $currentEmulator",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Botón de expandir configuración (sync y export): sin
                // sentido si todas las funciones configurables están
                // desactivadas.
                if (syncEnabled || esdeEnabled || retroHraiEnabled) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = "Configurar sync",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = platform.visible,
                    onCheckedChange = { onToggle(platform) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                )
            }

            // Panel expandible con config de sync
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                if (syncEnabled) {
                Text(
                    "Configuración de sync",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Selector de emulador
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    availableEmulators.forEach { emu ->
                        FilterChip(
                            selected = currentEmulator == emu.id,
                            onClick = {
                                onEmulatorChange(
                                    platform.id,
                                    if (emu.id == defaultEmulator.id) null else emu.id,
                                )
                            },
                            label = { Text(emu.displayName) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Override de ruta de saves mediante explorador de carpetas propio
                var showFolderPicker by remember { mutableStateOf(false) }
                val hasOverride = !platform.savesPathOverride.isNullOrBlank()
                val displayedPath = platform.savesPathOverride?.takeIf { it.isNotBlank() }
                    ?: defaultSavesPath

                Text(
                    "Ruta de saves",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    displayedPath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (hasOverride) "Ruta personalizada" else "Ruta por defecto",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { showFolderPicker = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Seleccionar carpeta")
                    }
                    if (hasOverride) {
                        TextButton(
                            onClick = { onSavesPathChange(platform.id, null) },
                        ) {
                            Text("Restablecer")
                        }
                    }
                }

                if (showFolderPicker) {
                    es.davidrg.rommsync.ui.components.FolderPickerDialog(
                        initialPath = displayedPath,
                        onDismiss = { showFolderPicker = false },
                        onSelect = { selected ->
                            onSavesPathChange(platform.id, selected)
                            showFolderPicker = false
                        },
                    )
                }
                } // end if (syncEnabled)

                // ── Exportar metadatos (ES-DE / RetroHRAI) ────────────────
                // Visible si al menos uno de los dos frontends está activado
                // en Configuración.
                if (esdeEnabled || retroHraiEnabled) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (esdeEnabled) "Exportar a ES-DE" else "Exportar a RetroHRAI",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (esdeEnabled) {
                    Text(
                        "Descarga carátulas, screenshots, vídeos y manuales de RomM " +
                            "y actualiza el gamelist.xml de esta plataforma.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Descarga carátulas, screenshots, vídeos y manuales de RomM " +
                            "a la estructura de carpetas de RetroHRAI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                val exportState by container.metadataExportManager
                    .observeExportState(platform.slug).collectAsState(
                        initial = es.davidrg.rommsync.data.metadata.ExportState.Idle
                    )
                val isExporting = exportState is es.davidrg.rommsync.data.metadata.ExportState.Running ||
                    exportState is es.davidrg.rommsync.data.metadata.ExportState.Pending

                // Toggle: incluir RetroHRAI además de ES-DE (solo si ambos
                // frontends están activados)
                var exportRetroHrai by remember { mutableStateOf(false) }

                if (retroHraiEnabled && esdeEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = exportRetroHrai,
                            onCheckedChange = { exportRetroHrai = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            "Exportar también a RetroHRAI",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                FilledTonalButton(
                    onClick = {
                        container.metadataExportManager.triggerExport(
                            platformId = platform.id,
                            platformSlug = platform.slug,
                            gamelist = esdeEnabled,
                            retroHrai = retroHraiEnabled && (exportRetroHrai || !esdeEnabled),
                        )
                    },
                    enabled = !isExporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(if (isExporting) "Exportando..." else "Exportar metadatos")
                }

                // Feedback de resultado
                when (exportState) {
                    is es.davidrg.rommsync.data.metadata.ExportState.Success -> {
                        val s = exportState as es.davidrg.rommsync.data.metadata.ExportState.Success
                        Text(
                            "✓ ${s.mediaDownloaded} archivos · ${s.entriesCount} juegos en gamelist",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    is es.davidrg.rommsync.data.metadata.ExportState.Failed -> {
                        Text(
                            "Error en la exportación",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    else -> {}
                }
                } // end if (esdeEnabled || retroHraiEnabled)
            }
        }
    }
}

/** 1_500_000_000 → "1.4 GB" para la tarjeta de estadísticas. */
private fun formatStatsBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    val mb = bytes / 1_000_000.0
    return when {
        gb >= 1 -> String.format(java.util.Locale.getDefault(), "%.1f GB", gb)
        mb >= 1 -> String.format(java.util.Locale.getDefault(), "%.0f MB", mb)
        else -> "${bytes / 1000} KB"
    }
}
