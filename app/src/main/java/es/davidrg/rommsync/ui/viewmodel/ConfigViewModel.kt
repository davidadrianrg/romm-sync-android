package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.data.local.ServerConfig
import es.davidrg.rommsync.data.repository.RomRepository
import es.davidrg.rommsync.data.repository.SettingsRepository
import es.davidrg.rommsync.data.sync.SaveSyncManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConfigViewModel(
    private val settingsRepository: SettingsRepository,
    private val romRepository: RomRepository? = null,
    private val saveSyncManager: SaveSyncManager? = null,
) : ViewModel() {

    val settings: StateFlow<ServerConfig> = settingsRepository.settings
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ServerConfig("", "", "", 2),
        )

    // ── Escaneo de biblioteca ──────────────────────────────────────────
    private val _scanState = MutableStateFlow<LibraryScanState>(LibraryScanState.Idle)
    val scanState: StateFlow<LibraryScanState> = _scanState.asStateFlow()

    /**
     * Recorre la biblioteca en disco y marca como descargados los juegos cuyo
     * fichero exista en la ruta esperada.
     */
    fun scanLibrary(serverUrl: String, apiKey: String) {
        val repo = romRepository ?: return
        if (_scanState.value is LibraryScanState.Scanning) return
        viewModelScope.launch {
            _scanState.value = LibraryScanState.Scanning(null)
            repo.configureApi(serverUrl, apiKey)
            val romsRootPath = settingsRepository.settings.first().romsRootPath
            val result = repo.scanDownloadedLibrary(
                romsRootPath = romsRootPath,
                onProgress = { platformName ->
                    _scanState.value = LibraryScanState.Scanning(platformName)
                },
            )
            _scanState.value = if (result.isSuccess) {
                LibraryScanState.Done(detected = result.detected, scanned = result.scanned)
            } else {
                LibraryScanState.Error(result.error ?: "Error durante el escaneo")
            }
        }
    }

    fun dismissScanResult() {
        _scanState.value = LibraryScanState.Idle
    }

    fun setServerUrl(url: String) {
        viewModelScope.launch { settingsRepository.setServerUrl(url) }
    }

    fun setApiKey(key: String) {
        viewModelScope.launch { settingsRepository.setApiKey(key) }
    }

    fun setRomsRootPath(path: String) {
        viewModelScope.launch { settingsRepository.setRomsRootPath(path) }
    }

    fun setMaxConcurrentDownloads(max: Int) {
        viewModelScope.launch { settingsRepository.setMaxConcurrentDownloads(max) }
    }

    fun setWifiOnlyDownloads(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWifiOnlyDownloads(enabled) }
    }

    fun setRetroArchBasePath(path: String) {
        viewModelScope.launch { settingsRepository.setRetroArchBasePath(path) }
    }

    /**
     * Activa/desactiva la función de sincronización de saves. Al desactivar
     * cancela cualquier trabajo de sync encolado; al reactivar restaura el
     * periodo configurado si existía.
     */
    fun setSaveSyncEnabled(enabled: Boolean) {
        val manager = saveSyncManager ?: return
        viewModelScope.launch {
            settingsRepository.setSaveSyncEnabled(enabled)
            if (enabled) {
                val interval = settingsRepository.saveSyncIntervalMinutes.first()
                if (interval > 0) {
                    manager.schedulePeriodicSync(interval)
                }
            } else {
                manager.cancelAllSyncWork()
            }
        }
    }

    fun setEsdeDataPath(path: String) {
        viewModelScope.launch { settingsRepository.setEsdeDataDir(path) }
    }

    fun setRetroHraiMediaPath(path: String) {
        viewModelScope.launch { settingsRepository.setRetroHraiMediaPath(path) }
    }

    /** Muestra/oculta la exportación de metadatos a ES-DE. */
    fun setEsdeExportEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setEsdeExportEnabled(enabled) }
    }

    /** Muestra/oculta la exportación de metadatos a RetroHRAI. */
    fun setRetroHraiExportEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setRetroHraiExportEnabled(enabled) }
    }
}

/**
 * Estado del escaneo de biblioteca local.
 */
sealed class LibraryScanState {
    data object Idle : LibraryScanState()
    data class Scanning(val platformName: String?) : LibraryScanState()
    data class Done(val detected: Int, val scanned: Int) : LibraryScanState()
    data class Error(val message: String) : LibraryScanState()
}
