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
    suspend fun setRetroArchBasePath(path: String) = dataStore.setRetroArchBasePath(path)
    suspend fun setSaveSyncEnabled(enabled: Boolean) = dataStore.setSaveSyncEnabled(enabled)

    val retroArchBasePath: Flow<String> = dataStore.retroArchBasePath
    val saveSyncEnabled: Flow<Boolean> = dataStore.saveSyncEnabled
    val lastSyncTimestamp: Flow<Long> = dataStore.lastSyncTimestamp
    val lastSyncSummary: Flow<String> = dataStore.lastSyncSummary
    val esdeDataDir: Flow<String> = dataStore.esdeDataDir
    val retroHraiMediaPath: Flow<String> = dataStore.retroHraiMediaPath

    suspend fun setLastSync(timestamp: Long, summary: String) =
        dataStore.setLastSync(timestamp, summary)
    suspend fun setEsdeDataDir(path: String) = dataStore.setEsdeDataDir(path)
    suspend fun setRetroHraiMediaPath(path: String) = dataStore.setRetroHraiMediaPath(path)
}
