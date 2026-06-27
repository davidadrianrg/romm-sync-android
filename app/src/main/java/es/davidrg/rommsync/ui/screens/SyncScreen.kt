package es.davidrg.rommsync.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.data.sync.SyncState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container
    val scope = rememberCoroutineScope()

    val syncState by container.saveSyncManager.observeSyncState().collectAsState(initial = SyncState.Idle)
    val lastTimestamp by container.settingsRepository.lastSyncTimestamp.collectAsState(initial = 0L)
    val lastSummary by container.settingsRepository.lastSyncSummary.collectAsState(initial = "")
    val settings by container.settingsRepository.settings.collectAsState(
        initial = es.davidrg.rommsync.data.local.ServerConfig("", "", "", 2)
    )

    // Persistir resultado cuando el sync termina con éxito
    LaunchedEffect(syncState) {
        when (val s = syncState) {
            is SyncState.Success -> {
                container.settingsRepository.setLastSync(System.currentTimeMillis(), s.message)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Tarjeta de estado ─────────────────────────────────────
            StatusCard(
                syncState = syncState,
                lastTimestamp = lastTimestamp,
                lastSummary = lastSummary,
            )

            // ── Botón de sincronizar ──────────────────────────────────
            val isRunning = syncState is SyncState.Running || syncState is SyncState.Pending
            Button(
                onClick = { container.saveSyncManager.triggerSync() },
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
                )
            }

            // ── Información ───────────────────────────────────────────
            InfoCard()
        }
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

            // Resumen de la última sincronización si está idle
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

/**
 * Parsea el mensaje de resultado del SyncWorker.
 * Formato esperado: "3 completadas, 1 conflictos" o "Todo sincronizado"
 */
@Composable
private fun parseSyncResult(message: String): Quad<ImageVector, androidx.compose.ui.graphics.Color, String, String> {
    // Mostrar el mensaje del worker como subtítulo
    return if (message.contains("Todo sincronizado", ignoreCase = true)) {
        Quad(
            Icons.Filled.CheckCircle,
            MaterialTheme.colorScheme.secondary,
            "Sincronización completada",
            "Todo al día",
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

@Composable
private fun InfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(10.dp))
                Text(
                    "¿Qué se sincroniza?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "• Saves (.srm) y states (.state) de tus ROMs descargados\n" +
                    "• Comparación por fecha de modificación\n" +
                    "• Subida y bajada bidireccional\n" +
                    "• Handlers para RetroArch, melonDS, PPSSPP, PS2, Dolphin, Switch, 3DS y Wii U\n\n" +
                    "Configura la ruta de saves de cada plataforma en la pestaña Plataformas.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────

private data class Quad<A, B, C, D>(
    val first: A, val second: B, val third: C, val fourth: D,
)

private fun <A, B, C, D> Quad(first: A, second: B, third: C, fourth: D) = Quad(first, second, third, fourth)

private fun formatTimestamp(millis: Long): String {
    if (millis <= 0) return ""
    val sdf = SimpleDateFormat("dd/MM/yyyy 'a las' HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}
