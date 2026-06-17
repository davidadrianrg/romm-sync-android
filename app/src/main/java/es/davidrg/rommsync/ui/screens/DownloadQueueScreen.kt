package es.davidrg.rommsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import es.davidrg.rommsync.domain.model.DownloadTask
import es.davidrg.rommsync.ui.viewmodel.DownloadQueueViewModel

@Composable
fun DownloadQueueScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container

    val viewModel: DownloadQueueViewModel = viewModel(
        factory = viewModelFactory {
            initializer { DownloadQueueViewModel(container.downloadManager) }
        }
    )

    val downloads by viewModel.downloads.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cola de Descargas") },
                actions = {
                    if (downloads.any { it.isRunning }) {
                        Button(
                            onClick = { viewModel.cancelAll() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            ),
                        ) { Text("Cancelar todo") }
                    }
                },
            )
        }
    ) { padding ->
        if (downloads.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "No hay descargas en cola",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(downloads, key = { it.workId }) { task ->
                DownloadRow(
                    task = task,
                    onCancel = { viewModel.cancelDownload(task.romId) },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(task: DownloadTask, onCancel: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status icon
        when {
            task.isRunning && task.isIndeterminate -> {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 12.dp),
                    strokeWidth = 2.dp,
                )
            }
            task.isCompleted -> {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Completado",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            task.isFailed -> {
                Icon(
                    Icons.Filled.Error,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
            task.isRunning -> {
                CircularProgressIndicator(
                    progress = { task.progress / 100f },
                    modifier = Modifier.padding(end = 12.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.romName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = "${task.platformSlug} • ${task.fileName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (task.isRunning) {
                if (task.isIndeterminate) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Empaquetando (mod_zip)...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { task.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${task.progress}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (task.isCompleted) {
                Text(
                    "Descarga completada",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else if (task.isFailed) {
                Text(
                    "Error en la descarga. Reintentando...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Cancel button
        if (task.isRunning) {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Filled.Cancel,
                    contentDescription = "Cancelar",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
