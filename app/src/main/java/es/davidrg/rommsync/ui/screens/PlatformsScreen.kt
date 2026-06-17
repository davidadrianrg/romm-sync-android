package es.davidrg.rommsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
                actions = {
                    if (platforms.isNotEmpty()) {
                        // Toggle all/none button
                        OutlinedButton(
                            onClick = { viewModel.setAllVisible(!allVisible) },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(
                                if (allVisible) Icons.Filled.Deselect else Icons.Filled.SelectAll,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            Text(if (allVisible) "Ninguna" else "Todas", style = MaterialTheme.typography.labelMedium)
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
                Text(
                    "Configura el servidor primero en la pestaña Configuración.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${platforms.count { it.visible }} / ${platforms.size} plataformas",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { viewModel.refreshPlatforms(settings.serverUrl, settings.apiKey) },
                    enabled = !isLoading,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text("Actualizar")
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(platforms, key = { it.id }) { platform ->
                    PlatformRow(
                        platform = platform,
                        onToggle = { viewModel.togglePlatformVisibility(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlatformRow(platform: Platform, onToggle: (Platform) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(platform.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${platform.slug} • ${platform.romCount} ROMs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = platform.visible,
            onCheckedChange = { onToggle(platform) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}
