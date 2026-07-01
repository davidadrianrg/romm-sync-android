package es.davidrg.rommsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.sync.SyncState
import es.davidrg.rommsync.ui.components.FolderPickerDialog
import es.davidrg.rommsync.ui.viewmodel.SavePreviewItem
import es.davidrg.rommsync.ui.viewmodel.SyncViewModel
import es.davidrg.rommsync.util.isIgnoringBatteryOptimizations
import es.davidrg.rommsync.util.requestIgnoreBatteryOptimizations
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container
    val database = es.davidrg.rommsync.data.local.RomSyncDatabase.getDatabase(context)

    val viewModel: SyncViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                SyncViewModel(
                    settingsRepository = container.settingsRepository,
                    saveSyncManager = container.saveSyncManager,
                    romDao = database.romDao(),
                    platformDao = database.platformDao(),
                )
            }
        }
    )

    val settings by container.settingsRepository.settings.collectAsState(
        initial = es.davidrg.rommsync.data.local.ServerConfig("", "", "", 2)
    )
    val syncState by viewModel.syncState.collectAsState()
    val lastTimestamp by viewModel.lastSyncTimestamp.collectAsState()
    val lastSummary by viewModel.lastSyncSummary.collectAsState()
    val retroArchBasePath by viewModel.retroArchBasePath.collectAsState()
    val syncInterval by viewModel.syncIntervalMinutes.collectAsState()
    val localSaves by viewModel.localSaves.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()

    var retroArchPath by remember { mutableStateOf("") }
    var showFolderPicker by remember { mutableStateOf(false) }

    LaunchedEffect(retroArchBasePath) {
        if (retroArchPath.isEmpty() && retroArchBasePath.isNotEmpty()) {
            retroArchPath = retroArchBasePath
        }
    }

    // Persistir resultado cuando el sync termina
    LaunchedEffect(syncState) {
        when (val s = syncState) {
            is SyncState.Success -> {
                container.settingsRepository.setLastSync(System.currentTimeMillis(), s.message)
                viewModel.scanLocalSaves()
            }
            is SyncState.Failed -> {
                container.settingsRepository.setLastSync(System.currentTimeMillis(), "Error: ${s.message}")
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sincronización") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
        ) {
            // -- Estado actual
            item { StatusCard(syncState, lastTimestamp, lastSummary) }

            // -- Boton de sincronizar
            item {
                val isRunning = syncState is SyncState.Running || syncState is SyncState.Pending
                Button(
                    onClick = { viewModel.triggerSync() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = settings.isConfigured && !isRunning,
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.size(10.dp))
                        Text(
                            if (syncState is SyncState.Pending) "En cola..." else "Sincronizando...",
                        )
                    } else {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Sincronizar ahora")
                    }
                }

                if (!settings.isConfigured) {
                    Text(
                        "Configura el servidor RomM antes de sincronizar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // -- Ruta base RetroArch
            item {
                SyncSection(
                    icon = Icons.Outlined.Storage,
                    title = "Ruta base RetroArch",
                ) {
                    OutlinedTextField(
                        value = retroArchPath,
                        onValueChange = {
                            retroArchPath = it
                            viewModel.setRetroArchBasePath(it)
                        },
                        label = { Text("Ruta base") },
                        placeholder = { Text("/storage/emulated/0/RetroArch") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { showFolderPicker = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Filled.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Seleccionar carpeta")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Se usa como base para plataformas asignadas a RetroArch. " +
                            "Puedes personalizar la ruta de cada plataforma en su configuración.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // -- Sync periodico
            item {
                SyncSection(
                    icon = Icons.Outlined.Schedule,
                    title = "Sincronización automática",
                ) {
                    Text(
                        if (syncInterval > 0) "Cada $syncInterval minutos"
                        else "Desactivada",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val options = listOf(0, 15, 30, 60, 120)
                        val labels = listOf("Off", "15m", "30m", "1h", "2h")
                        options.forEachIndexed { index, minutes ->
                            FilterChip(
                                selected = syncInterval == minutes,
                                onClick = { viewModel.setSyncInterval(minutes) },
                                label = { Text(labels[index]) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Requiere conexión a internet. La sincronización se ejecuta en segundo plano " +
                            "incluso con la app cerrada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Aviso de optimización de batería
                    if (syncInterval > 0 && !isIgnoringBatteryOptimizations(context)) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "Optimización de batería activa",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "El sistema puede impedir la sincronización en segundo plano. " +
                                        "Desactiva la optimización de batería para que funcione de forma fiable.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                FilledTonalButton(
                                    onClick = { requestIgnoreBatteryOptimizations(context) },
                                ) {
                                    Text("Desactivar restricción")
                                }
                            }
                        }
                    }
                }
            }

            // -- Preview de saves locales
            item {
                SyncSection(
                    icon = Icons.Outlined.Info,
                    title = "Saves locales detectados",
                ) {
                    if (isScanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                "Escaneando...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (localSaves.isEmpty()) {
                        Text(
                            "No se encontraron saves locales para los ROMs descargados. " +
                                "Descarga algún ROM y juega para generar archivos de guardado.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "${localSaves.size} archivo${if (localSaves.size != 1) "s" else ""} " +
                                "de guardado encontrado${if (localSaves.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Estos archivos se compararán con el servidor al sincronizar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { viewModel.scanLocalSaves() },
                        ) {
                            Icon(
                                Icons.Outlined.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Reescanear")
                        }
                    }
                }
            }

            // -- Lista de saves
            if (!isScanning && localSaves.isNotEmpty()) {
                val grouped = localSaves.groupBy { "${it.platformSlug} - ${it.romName}" }
                grouped.forEach { (groupKey, saves) ->
                    item(key = "header_$groupKey") {
                        Text(
                            groupKey,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(saves, key = { "${it.platformSlug}_${it.romName}_${it.fileName}" }) { save ->
                        SaveItemRow(save)
                    }
                    item(key = "divider_$groupKey") {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }

    // Folder picker dialog
    if (showFolderPicker) {
        FolderPickerDialog(
            initialPath = retroArchBasePath.ifBlank {
                SettingsDataStore.DEFAULT_RETROARCH_PATH
            },
            onDismiss = { showFolderPicker = false },
            onSelect = { path ->
                viewModel.setRetroArchBasePath(path)
                retroArchPath = path
                showFolderPicker = false
            },
        )
    }
}

@Composable
private fun StatusCard(
    syncState: SyncState,
    lastTimestamp: Long,
    lastSummary: String,
) {
    val (icon, iconTint, title, subtitle) = when (syncState) {
        SyncState.Idle -> {
            if (lastTimestamp > 0) {
                Quad(
                    Icons.Filled.CheckCircle,
                    MaterialTheme.colorScheme.secondary,
                    "Última sincronización",
                    formatTimestamp(lastTimestamp),
                )
            } else {
                Quad(
                    Icons.Outlined.Sync,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    "Sin sincronizar",
                    "Pulsa el botón para sincronizar tus saves",
                )
            }
        }
        SyncState.Pending -> Quad(
            Icons.Filled.Sync,
            MaterialTheme.colorScheme.primary,
            "En cola",
            "Esperando conexión...",
        )
        SyncState.Running -> Quad(
            Icons.Filled.Sync,
            MaterialTheme.colorScheme.primary,
            "Sincronizando",
            "Conectando con el servidor RomM...",
        )
        is SyncState.Success -> parseSyncResult(syncState.message)
        is SyncState.Failed -> Quad(
            Icons.Filled.Error,
            MaterialTheme.colorScheme.error,
            "Error",
            syncState.message,
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (syncState is SyncState.Running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 4.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(48.dp),
                )
            }

            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (syncState is SyncState.Idle && lastSummary.isNotBlank() && lastTimestamp > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Resultado: ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        lastSummary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveItemRow(save: SavePreviewItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                save.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            formatTimestamp(save.lastModified),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SyncSection(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun parseSyncResult(message: String): Quad<ImageVector, androidx.compose.ui.graphics.Color, String, String> {
    return if (message.contains("Todo sincronizado", ignoreCase = true)) {
        Quad(
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.secondary,
            "Todo al día",
            "No hay cambios pendientes",
        )
    } else {
        Quad(
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.secondary,
            "Sincronización completada",
            message,
        )
    }
}

private data class Quad<A, B, C, D>(
    val first: A, val second: B, val third: C, val fourth: D,
)

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0) return ""
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}
