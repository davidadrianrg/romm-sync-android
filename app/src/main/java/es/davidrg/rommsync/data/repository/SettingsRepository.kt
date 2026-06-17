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
}
