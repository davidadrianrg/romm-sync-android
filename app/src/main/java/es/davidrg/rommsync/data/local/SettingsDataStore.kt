package es.davidrg.rommsync.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "romsync_settings")

/**
 * Persistent app configuration.
 *
 * Server URL, root ROMs directory and max concurrent downloads are stored via
 * Jetpack DataStore (Preferences). The **API key** is stored separately using
 * [EncryptedSharedPreferences] (AES256-GCM) so that it is never persisted in
 * plaintext and is never passed through WorkManager `Data`.
 */
class SettingsDataStore(private val context: Context) {

    companion object {
        // ── DataStore keys (non-sensitive settings) ────────────────────
        val SERVER_URL = stringPreferencesKey("server_url")
        val ROMS_ROOT_PATH = stringPreferencesKey("roms_root_path")
        val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")

        /**
         * Legacy DataStore key where the API key used to live (plaintext).
         * Kept only to support one-time migration; the value is deleted after
         * being copied into EncryptedSharedPreferences.
         */
        private val LEGACY_API_KEY = stringPreferencesKey("api_key")

        // ── EncryptedSharedPreferences key ─────────────────────────────
        private const val SECURE_PREFS_FILE = "romsync_secure_prefs"
        private const val SECURE_KEY_API_KEY = "api_key"

        const val DEFAULT_ROMS_PATH = "/storage/emulated/0/ROMs"
        const val DEFAULT_MAX_DOWNLOADS = 2
    }

    /**
     * Scope for the one-time legacy migration launched from [init].
     */
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Encrypted storage ─────────────────────────────────────────────

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        SECURE_PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /**
     * Hot, reactive holder for the API key. Seeded synchronously from the
     * encrypted store on construction and updated whenever [setApiKey] runs.
     */
    private val _apiKeyFlow = MutableStateFlow(securePrefs.getString(SECURE_KEY_API_KEY, "") ?: "")

    init {
        // One-time migration: if a previous app version stored the API key in
        // the (plaintext) DataStore, move it to EncryptedSharedPreferences and
        // remove it from DataStore.
        migrationScope.launch { migrateApiKeyIfNeeded() }
    }

    private suspend fun migrateApiKeyIfNeeded() {
        val legacyKey = context.dataStore.data.first()[LEGACY_API_KEY]
        if (legacyKey.isNullOrEmpty()) return

        // Only copy if the encrypted store doesn't already have one.
        if (securePrefs.getString(SECURE_KEY_API_KEY, "").isNullOrEmpty()) {
            securePrefs.edit().putString(SECURE_KEY_API_KEY, legacyKey).apply()
            _apiKeyFlow.value = legacyKey
        }
        // Always wipe the plaintext copy.
        context.dataStore.edit { it.remove(LEGACY_API_KEY) }
    }

    // ── Reactive flows (Compose / ViewModel) ──────────────────────────

    val serverUrl: Flow<String> = context.dataStore.data.map { it[SERVER_URL] ?: "" }
    val apiKey: Flow<String> = _apiKeyFlow
    val romsRootPath: Flow<String> = context.dataStore.data.map { it[ROMS_ROOT_PATH] ?: DEFAULT_ROMS_PATH }
    val maxConcurrentDownloads: Flow<Int> = context.dataStore.data.map {
        it[MAX_CONCURRENT_DOWNLOADS] ?: DEFAULT_MAX_DOWNLOADS
    }

    /** Combined settings snapshot */
    val settings: Flow<ServerConfig> = combine(
        context.dataStore.data,
        _apiKeyFlow,
    ) { prefs, apiKey ->
        ServerConfig(
            serverUrl = prefs[SERVER_URL] ?: "",
            apiKey = apiKey,
            romsRootPath = prefs[ROMS_ROOT_PATH] ?: DEFAULT_ROMS_PATH,
            maxConcurrentDownloads = prefs[MAX_CONCURRENT_DOWNLOADS] ?: DEFAULT_MAX_DOWNLOADS,
        )
    }

    // ── Writers ───────────────────────────────────────────────────────

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[SERVER_URL] = url.trimEnd('/') }
    }

    /**
     * Stores the API key in EncryptedSharedPreferences.
     * Also updates the reactive [_apiKeyFlow] so UI consumers refresh.
     */
    suspend fun setApiKey(key: String) {
        securePrefs.edit().putString(SECURE_KEY_API_KEY, key).apply()
        _apiKeyFlow.value = key
    }

    suspend fun setRomsRootPath(path: String) {
        context.dataStore.edit { it[ROMS_ROOT_PATH] = path }
    }

    suspend fun setMaxConcurrentDownloads(max: Int) {
        context.dataStore.edit { it[MAX_CONCURRENT_DOWNLOADS] = max.coerceIn(1, 5) }
    }

    // ── Blocking readers (for WorkManager / non-coroutine callers) ────

    /**
     * Synchronous read of the ROMs root path for use in WorkManager.
     */
    fun getRomsRootPathBlocking(): String {
        return runCatching {
            runBlocking { context.dataStore.data.first()[ROMS_ROOT_PATH] ?: DEFAULT_ROMS_PATH }
        }.getOrDefault(DEFAULT_ROMS_PATH)
    }

    /**
     * Synchronous read of the API key from EncryptedSharedPreferences.
     * Safe to call from a Worker's `doWork()` since SharedPreferences access
     * is already blocking and the data is already decrypted in memory.
     */
    fun getApiKeyBlocking(): String {
        return runCatching {
            securePrefs.getString(SECURE_KEY_API_KEY, "") ?: ""
        }.getOrDefault("")
    }

    /**
     * Synchronous read of the server URL for use in WorkManager.
     */
    fun getServerUrlBlocking(): String {
        return runCatching {
            runBlocking { context.dataStore.data.first()[SERVER_URL] ?: "" }
        }.getOrDefault("")
    }

    /**
     * Synchronous read of the max concurrent downloads setting for use in
     * WorkManager (the [DownloadWorker] reads this to size its concurrency
     * semaphore before each run).
     *
     * The value is always coerced to the valid 1-5 range.
     */
    fun getMaxConcurrentDownloadsBlocking(): Int {
        return runCatching {
            runBlocking {
                context.dataStore.data.first()[MAX_CONCURRENT_DOWNLOADS]
                    ?: DEFAULT_MAX_DOWNLOADS
            }
        }.getOrDefault(DEFAULT_MAX_DOWNLOADS).coerceIn(1, 5)
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
