package es.davidrg.rommsync.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "romsync_settings")

/**
 * Persistent app configuration via Jetpack DataStore.
 * Stores: server URL, API key, root ROMs directory, max concurrent downloads.
 */
class SettingsDataStore(private val context: Context) {

    companion object {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key")
        val ROMS_ROOT_PATH = stringPreferencesKey("roms_root_path")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")

        const val DEFAULT_ROMS_PATH = "/storage/emulated/0/ROMs"
        const val DEFAULT_MAX_DOWNLOADS = 2
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[SERVER_URL] ?: "" }
    val apiKey: Flow<String> = context.dataStore.data.map { it[API_KEY] ?: "" }
    val romsRootPath: Flow<String> = context.dataStore.data.map { it[ROMS_ROOT_PATH] ?: DEFAULT_ROMS_PATH }
    val maxConcurrentDownloads: Flow<Int> = context.dataStore.data.map {
        it[MAX_CONCURRENT_DOWNLOADS] ?: DEFAULT_MAX_DOWNLOADS
    }

    /** Combined settings snapshot */
    val settings: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
        ServerConfig(
            serverUrl = prefs[SERVER_URL] ?: "",
            apiKey = prefs[API_KEY] ?: "",
            romsRootPath = prefs[ROMS_ROOT_PATH] ?: DEFAULT_ROMS_PATH,
            maxConcurrentDownloads = prefs[MAX_CONCURRENT_DOWNLOADS] ?: DEFAULT_MAX_DOWNLOADS,
        )
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[SERVER_URL] = url.trimEnd('/') }
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[API_KEY] = key }
    }

    suspend fun setRomsRootPath(path: String) {
        context.dataStore.edit { it[ROMS_ROOT_PATH] = path }
    }

    suspend fun setMaxConcurrentDownloads(max: Int) {
        context.dataStore.edit { it[MAX_CONCURRENT_DOWNLOADS] = max.coerceIn(1, 5) }
    }

    /**
     * Synchronous read of ROMs root path for use in WorkManager (blocking context).
     */
    fun getRomsRootPathBlocking(): String {
        return runCatching {
            runBlocking { context.dataStore.data.first()[ROMS_ROOT_PATH] ?: DEFAULT_ROMS_PATH }
        }.getOrDefault(DEFAULT_ROMS_PATH)
    }
}

data class ServerConfig(
    val serverUrl: String,
    val apiKey: String,
    val romsRootPath: String,
    val maxConcurrentDownloads: Int,
) {
    val isConfigured: Boolean get() = serverUrl.isNotEmpty() && apiKey.isNotEmpty()
}
