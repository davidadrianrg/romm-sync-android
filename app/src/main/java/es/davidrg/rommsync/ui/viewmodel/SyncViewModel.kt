package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.local.dao.PlatformDao
import es.davidrg.rommsync.data.local.dao.RomDao
import es.davidrg.rommsync.data.local.entity.DownloadedRomEntity
import es.davidrg.rommsync.data.repository.SettingsRepository
import es.davidrg.rommsync.data.sync.SaveSyncManager
import es.davidrg.rommsync.data.sync.SyncState
import es.davidrg.rommsync.data.sync.platform.LocalSave
import es.davidrg.rommsync.data.sync.platform.SaveHandlerRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modelo de datos para mostrar una partida local en la preview.
 */
data class SavePreviewItem(
    val romName: String,
    val platformSlug: String,
    val fileName: String,
    val lastModified: Long,
)

class SyncViewModel(
    private val settingsRepository: SettingsRepository,
    private val saveSyncManager: SaveSyncManager,
    private val romDao: RomDao,
    private val platformDao: PlatformDao,
) : ViewModel() {

    val retroArchBasePath: StateFlow<String> = settingsRepository.retroArchBasePath
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsDataStore.DEFAULT_RETROARCH_PATH)

    val syncState: StateFlow<SyncState> = saveSyncManager.observeSyncState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncState.Idle)

    val lastSyncTimestamp: StateFlow<Long> = settingsRepository.lastSyncTimestamp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val lastSyncSummary: StateFlow<String> = settingsRepository.lastSyncSummary
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val syncIntervalMinutes: StateFlow<Int> = settingsRepository.saveSyncIntervalMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _localSaves = MutableStateFlow<List<SavePreviewItem>>(emptyList())
    val localSaves: StateFlow<List<SavePreviewItem>> = _localSaves.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    init {
        scanLocalSaves()
    }

    fun setRetroArchBasePath(path: String) {
        viewModelScope.launch {
            settingsRepository.setRetroArchBasePath(path)
            scanLocalSaves()
        }
    }

    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch {
            settingsRepository.setSaveSyncIntervalMinutes(minutes)
            saveSyncManager.schedulePeriodicSync(minutes, replace = true)
        }
    }

    fun triggerSync() {
        saveSyncManager.triggerSync()
    }

    fun scanLocalSaves() {
        viewModelScope.launch {
            _isScanning.value = true
            val saves = withContext(Dispatchers.IO) {
                scanSavesFromDisk()
            }
            _localSaves.value = saves
            _isScanning.value = false
        }
    }

    private suspend fun scanSavesFromDisk(): List<SavePreviewItem> {
        val retroArchBase = settingsRepository.retroArchBasePath
            .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsDataStore.DEFAULT_RETROARCH_PATH)
            .value

        val downloadedRoms = romDao.getAllDownloadedRoms()
        if (downloadedRoms.isEmpty()) return emptyList()

        val platformConfigs = platformDao.getAllPlatformsBlocking().associateBy { it.slug }
        val results = mutableListOf<SavePreviewItem>()

        for (rom in downloadedRoms) {
            val config = platformConfigs[rom.platformSlug]
            val emulatorId = config?.emulatorId
                ?: SaveHandlerRegistry.getDefaultEmulator(rom.platformSlug).id
            val handler = SaveHandlerRegistry.getHandler(
                platformSlug = rom.platformSlug,
                emulatorId = config?.emulatorId,
            )

            val effectiveBasePath = config?.savesPathOverride?.takeIf { it.isNotBlank() }
                ?: SaveHandlerRegistry.getDefaultSavesPath(
                    emulatorId = emulatorId,
                    platformSlug = rom.platformSlug,
                    retroArchBase = retroArchBase,
                )

            val saves = handler.findSaves(
                romId = rom.romId,
                romFileName = rom.fileName,
                platformSlug = rom.platformSlug,
                savesBasePath = effectiveBasePath,
            )

            for (save in saves) {
                results.add(
                    SavePreviewItem(
                        romName = rom.name,
                        platformSlug = rom.platformSlug,
                        fileName = save.fileName,
                        lastModified = save.lastModified,
                    )
                )
            }
        }

        return results.sortedByDescending { it.lastModified }
    }
}
