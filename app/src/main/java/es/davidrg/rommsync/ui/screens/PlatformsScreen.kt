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
    val settings by container.settingsRepository.settings.collectAsState(
        initial = es.davidrg.rommsync.data.local.ServerConfig("", "", "", 2)
    )

    val retroArchBasePath by container.settingsRepository.retroArchBasePath.collectAsState(
        initial = es.davidrg.rommsync.data.local.SettingsDataStore.DEFAULT_RETROARCH_PATH,
    )

    val allVisible = platforms.isNotEmpty() && platforms.all { it.visible }

    LaunchedEffect(settings.isConfigured) {
        if (settings.isConfigured && platforms.isEmpty()) {
            viewModel.refreshPlatforms(settings.serverUrl, settings.apiKey)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plataformas") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    if (platforms.isNotEmpty()) {
                        TextButton(onClick = { viewModel.setAllVisible(!allVisible) }) {
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
                EmptyState(
                    title = "Sin servidor configurado",
                    subtitle = "Configura el servidor en la pestaña Configuración para ver tus plataformas.",
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
                // Botón de expandir configuración de sync
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = "Configurar sync",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

                // ── Exportar metadatos a ES-DE ──────────────────────────
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Exportar a ES-DE",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Descarga carátulas, screenshots, vídeos y manuales de RomM " +
                        "y actualiza el gamelist.xml de esta plataforma.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))

                val exportState by container.metadataExportManager
                    .observeExportState(platform.slug).collectAsState(
                        initial = es.davidrg.rommsync.data.metadata.ExportState.Idle
                    )
                val isExporting = exportState is es.davidrg.rommsync.data.metadata.ExportState.Running ||
                    exportState is es.davidrg.rommsync.data.metadata.ExportState.Pending

                FilledTonalButton(
                    onClick = {
                        container.metadataExportManager.triggerExport(
                            platformId = platform.id,
                            platformSlug = platform.slug,
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
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Storage,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 32.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
