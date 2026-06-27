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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import es.davidrg.rommsync.RomMSyncApplication
import es.davidrg.rommsync.ui.components.FolderPickerDialog
import es.davidrg.rommsync.ui.viewmodel.ConfigViewModel
import es.davidrg.rommsync.util.hasAllFilesAccess
import es.davidrg.rommsync.util.rememberNotificationPermissionState

@OptIn(ExperimentalMaterial3Api::class)
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
    var retroArchPath by remember { mutableStateOf("") }

    // ── Folder picker dialog state ───────────────────────────────────────
    var showRomsPicker by remember { mutableStateOf(false) }
    var showRetroArchPicker by remember { mutableStateOf(false) }
    var showEsdePicker by remember { mutableStateOf(false) }

    LaunchedEffect(settings.serverUrl, settings.apiKey) {
        if (serverUrl.isEmpty() && settings.serverUrl.isNotEmpty()) serverUrl = settings.serverUrl
        if (apiKey.isEmpty() && settings.apiKey.isNotEmpty()) apiKey = settings.apiKey
    }

    // Cargar ruta de RetroArch
    val retroArchBasePath by container.settingsRepository.retroArchBasePath.collectAsState(
        initial = es.davidrg.rommsync.data.local.SettingsDataStore.DEFAULT_RETROARCH_PATH,
    )
    LaunchedEffect(retroArchBasePath) {
        if (retroArchPath.isEmpty() && retroArchBasePath.isNotEmpty()) {
            retroArchPath = retroArchBasePath
        }
    }

    // Cargar ruta de datos de ES-DE
    val esdeDataDir by container.settingsRepository.esdeDataDir.collectAsState(
        initial = es.davidrg.rommsync.data.local.SettingsDataStore.DEFAULT_ESDE_DATA_DIR,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración") },
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
            // ── Permiso de almacenamiento ───────────────────────────────
            if (!hasAllFilesAccess()) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.WarningAmber,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.size(10.dp))
                            Text(
                                "Permiso de almacenamiento requerido",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Concede acceso a todos los archivos para que la app pueda " +
                                "escribir las ROMs en la estructura de carpetas de ES-DE.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        FilledTonalButton(
                            onClick = { es.davidrg.rommsync.util.requestAllFilesAccess(context) },
                        ) {
                            Text("Conceder permiso")
                        }
                    }
                }
            }

            // ── Servidor ────────────────────────────────────────────────
            SettingsSection(
                icon = Icons.Outlined.Dns,
                title = "Servidor RomM",
            ) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("URL del servidor") },
                    placeholder = { Text("https://romm.midominio.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                    ),
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("rmm_...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
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
                                contentDescription = "Mostrar u ocultar API Key",
                            )
                        }
                    },
                )
            }

            // ── Almacenamiento ──────────────────────────────────────────
            SettingsSection(
                icon = Icons.Outlined.Folder,
                title = "Directorio de ROMs (ES-DE)",
            ) {
                OutlinedTextField(
                    value = settings.romsRootPath,
                    onValueChange = { viewModel.setRomsRootPath(it) },
                    label = { Text("Ruta raíz") },
                    placeholder = { Text("/storage/emulated/0/ROMs") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        IconButton(onClick = { showRomsPicker = true }) {
                            Icon(Icons.Filled.Folder, contentDescription = "Explorar carpetas")
                        }
                    },
                )
            }

            // ── ES-DE Data Dir ───────────────────────────────────────────
            SettingsSection(
                icon = Icons.Outlined.Folder,
                title = "Datos de ES-DE (gamelist)",
            ) {
                OutlinedTextField(
                    value = esdeDataDir,
                    onValueChange = { viewModel.setEsdeDataPath(it) },
                    label = { Text("Directorio de datos ES-DE") },
                    placeholder = { Text("/storage/emulated/0/ES-DE") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        IconButton(onClick = { showEsdePicker = true }) {
                            Icon(Icons.Filled.Folder, contentDescription = "Explorar carpetas")
                        }
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Aquí es donde ES-DE guarda los gamelist.xml. " +
                        "Los metadatos exportados se escribirán en esta carpeta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Descargas ───────────────────────────────────────────────
            SettingsSection(
                icon = Icons.Outlined.DownloadForOffline,
                title = "Descargas simultáneas",
            ) {
                Text(
                    "${settings.maxConcurrentDownloads} ${if (settings.maxConcurrentDownloads == 1) "descarga" else "descargas"} en paralelo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.maxConcurrentDownloads.toFloat(),
                    onValueChange = { viewModel.setMaxConcurrentDownloads(it.toInt()) },
                    valueRange = 1f..5f,
                    steps = 3,
                )
            }

            // ── Save Sync ───────────────────────────────────────────────
            SettingsSection(
                icon = Icons.Outlined.Sync,
                title = "Sincronización de saves",
            ) {
                OutlinedTextField(
                    value = retroArchPath,
                    onValueChange = { retroArchPath = it; viewModel.setRetroArchBasePath(it) },
                    label = { Text("Ruta base RetroArch") },
                    placeholder = { Text("/storage/emulated/0/RetroArch") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        IconButton(onClick = { showRetroArchPicker = true }) {
                            Icon(Icons.Filled.Folder, contentDescription = "Explorar carpetas")
                        }
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Esta ruta se usa como base para plataformas asignadas a RetroArch. " +
                        "Puedes configurar la ruta de saves de cada plataforma " +
                        "individualmente en la pestaña Plataformas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = { container.saveSyncManager.triggerSync() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = settings.isConfigured,
                ) {
                    Icon(
                        Icons.Outlined.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Sincronizar ahora")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // ── Guardar ─────────────────────────────────────────────────
            Button(
                onClick = {
                    viewModel.setServerUrl(serverUrl)
                    viewModel.setApiKey(apiKey)
                    container.configureApi(serverUrl, apiKey)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = serverUrl.isNotBlank() && apiKey.isNotBlank(),
            ) {
                Text("Guardar configuración", style = MaterialTheme.typography.labelLarge)
            }

            if (settings.isConfigured) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = "Servidor configurado",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        }
    }

    // ── Folder picker dialogs ───────────────────────────────────────────
    if (showRomsPicker) {
        FolderPickerDialog(
            initialPath = settings.romsRootPath.ifBlank {
                es.davidrg.rommsync.data.local.SettingsDataStore.DEFAULT_ROMS_PATH
            },
            onDismiss = { showRomsPicker = false },
            onSelect = { path ->
                viewModel.setRomsRootPath(path)
                showRomsPicker = false
            },
        )
    }
    if (showRetroArchPicker) {
        FolderPickerDialog(
            initialPath = retroArchBasePath.ifBlank {
                es.davidrg.rommsync.data.local.SettingsDataStore.DEFAULT_RETROARCH_PATH
            },
            onDismiss = { showRetroArchPicker = false },
            onSelect = { path ->
                viewModel.setRetroArchBasePath(path)
                retroArchPath = path
                showRetroArchPicker = false
            },
        )
    }
    if (showEsdePicker) {
        FolderPickerDialog(
            initialPath = esdeDataDir.ifBlank {
                es.davidrg.rommsync.data.local.SettingsDataStore.DEFAULT_ESDE_DATA_DIR
            },
            onDismiss = { showEsdePicker = false },
            onSelect = { path ->
                viewModel.setEsdeDataPath(path)
                showEsdePicker = false
            },
        )
    }
}

@Composable
private fun SettingsSection(
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
