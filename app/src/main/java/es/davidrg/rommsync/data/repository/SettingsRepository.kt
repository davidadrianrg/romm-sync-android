package es.davidrg.rommsync.data.repository

import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.local.ServerConfig
import kotlinx.coroutines.flow.Flow

/**
 * Wrapper around SettingsDataStore for clean access from ViewModels.
 */
class SettingsRepository(private val dataStore: SettingsDataStore) {
    val settings: Flow<ServerConfig> = dataStore.settings

    suspend fun setServerUrl(url: String) = dataStore.setServerUrl(url)
    suspend fun setApiKey(key: String) = dataStore.setApiKey(key)
    suspend fun setRomsRootPath(path: String) = dataStore.setRomsRootPath(path)
    suspend fun setMaxConcurrentDownloads(max: Int) = dataStore.setMaxConcurrentDownloads(max)
    suspend fun setWifiOnlyDownloads(enabled: Boolean) = dataStore.setWifiOnlyDownloads(enabled)
    suspend fun setRetroArchBasePath(path: String) = dataStore.setRetroArchBasePath(path)
    suspend fun setSaveSyncEnabled(enabled: Boolean) = dataStore.setSaveSyncEnabled(enabled)

    val retroArchBasePath: Flow<String> = dataStore.retroArchBasePath
    val saveSyncEnabled: Flow<Boolean> = dataStore.saveSyncEnabled
    val saveSyncIntervalMinutes: Flow<Int> = dataStore.saveSyncIntervalMinutes
    val lastSyncTimestamp: Flow<Long> = dataStore.lastSyncTimestamp
    val lastSyncSummary: Flow<String> = dataStore.lastSyncSummary
    val lastSyncConflictsJson: Flow<String> = dataStore.lastSyncConflictsJson
    val esdeDataDir: Flow<String> = dataStore.esdeDataDir
    val retroHraiMediaPath: Flow<String> = dataStore.retroHraiMediaPath
    val esdeExportEnabled: Flow<Boolean> = dataStore.esdeExportEnabled
    val retroHraiExportEnabled: Flow<Boolean> = dataStore.retroHraiExportEnabled

    suspend fun setLastSync(timestamp: Long, summary: String) =
        dataStore.setLastSync(timestamp, summary)
    suspend fun setLastSyncConflicts(json: String) =
        dataStore.setLastSyncConflictsJson(json)
    suspend fun setSaveSyncIntervalMinutes(minutes: Int) =
        dataStore.setSaveSyncIntervalMinutes(minutes)
    suspend fun setEsdeDataDir(path: String) = dataStore.setEsdeDataDir(path)
    suspend fun setRetroHraiMediaPath(path: String) = dataStore.setRetroHraiMediaPath(path)
    suspend fun setEsdeExportEnabled(enabled: Boolean) = dataStore.setEsdeExportEnabled(enabled)
    suspend fun setRetroHraiExportEnabled(enabled: Boolean) =
        dataStore.setRetroHraiExportEnabled(enabled)
}
