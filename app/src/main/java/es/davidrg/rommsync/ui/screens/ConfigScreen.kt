package es.davidrg.rommsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.ui.viewmodel.ConfigViewModel
import es.davidrg.rommsync.util.hasAllFilesAccess
import es.davidrg.rommsync.util.rememberNotificationPermissionState

@Composable
fun ConfigScreen() {
    val context = LocalContext.current
    val container = (context.applicationContext as RomMSyncApplication).container

    val viewModel: ConfigViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ConfigViewModel(container.settingsRepository) }
        }
    )

    val settings by viewModel.settings.collectAsState()
    rememberNotificationPermissionState()

    var serverUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }

    // Sync local state when settings load
    LaunchedEffectOnce(settings) {
        if (serverUrl.isEmpty()) serverUrl = settings.serverUrl
        if (apiKey.isEmpty()) apiKey = settings.apiKey
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
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
            // ── Storage permission warning ──────────────────────────────
            if (!hasAllFilesAccess()) {
                Text(
                    text = "⚠️ Permiso de almacenamiento no concedido. " +
                           "Concede acceso a todos los archivos para escribir ROMs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = {
                    es.davidrg.rommsync.util.requestAllFilesAccess(context)
                }) {
                    Text("Conceder permiso")
                }
                HorizontalDivider()
            }

            // ── Server URL ──────────────────────────────────────────────
            Text("Servidor RomM", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("URL del servidor") },
                placeholder = { Text("https://romm.midominio.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                ),
            )

            // ── API Key ─────────────────────────────────────────────────
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                placeholder = { Text("rmm_...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (apiKeyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            imageVector = if (apiKeyVisible) Icons.Filled.VisibilityOff
                                          else Icons.Filled.Visibility,
                            contentDescription = "Mostrar/ocultar API Key",
                        )
                    }
                },
            )

            // ── ROMs root path ──────────────────────────────────────────
            Text("Directorio de ROMs (ES-DE)", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = settings.romsRootPath,
                onValueChange = { viewModel.setRomsRootPath(it) },
                label = { Text("Ruta raíz") },
                placeholder = { Text("/storage/emulated/0/ROMs") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Icon(Icons.Filled.Folder, contentDescription = "Directorio")
                },
            )

            // ── Max concurrent downloads ────────────────────────────────
            Text("Descargas simultáneas: ${settings.maxConcurrentDownloads}",
                style = MaterialTheme.typography.titleMedium)

            Slider(
                value = settings.maxConcurrentDownloads.toFloat(),
                onValueChange = { viewModel.setMaxConcurrentDownloads(it.toInt()) },
                valueRange = 1f..5f,
                steps = 3,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Save button ─────────────────────────────────────────────
            Button(
                onClick = {
                    viewModel.setServerUrl(serverUrl)
                    viewModel.setApiKey(apiKey)
                    container.configureApi(serverUrl, apiKey)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = serverUrl.isNotBlank() && apiKey.isNotBlank(),
            ) {
                Text("Guardar configuración")
            }

            if (settings.isConfigured) {
                Text(
                    text = "✓ Servidor configurado",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

/**
 * Triggers [block] once when [key] changes. Avoids repeated calls on recomposition.
 */
@Composable
private fun <T> LaunchedEffectOnce(key: T, block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) {
        if (key is es.davidrg.rommsync.data.local.ServerConfig && key.isConfigured) {
            block()
        }
    }
}
