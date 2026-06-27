package es.davidrg.rommsync.data

import android.content.Context
import es.davidrg.rommsync.data.local.RomSyncDatabase
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.metadata.MetadataExportManager
import es.davidrg.rommsync.data.repository.RomRepository
import es.davidrg.rommsync.data.repository.SettingsRepository
import es.davidrg.rommsync.data.sync.SaveSyncManager
import es.davidrg.rommsync.download.DownloadManager

/**
 * Manual dependency injection container.
 *
 * Initialized once in [es.davidrg.rommsync.RomMSyncApplication.onCreate].
 * All ViewModels receive their dependencies from here via ViewModelFactory.
 *
 * This avoids the annotation-processing complexity of Hilt while keeping
 * a clean, testable architecture.
 */
class AppContainer(private val appContext: Context) {

    private val database: RomSyncDatabase = RomSyncDatabase.getDatabase(appContext)

    val settingsDataStore = SettingsDataStore(appContext)

    val settingsRepository = SettingsRepository(settingsDataStore)

    val romRepository = RomRepository(
        platformDao = database.platformDao(),
        romDao = database.romDao(),
    )

    val downloadManager = DownloadManager(appContext)

    val saveSyncManager = SaveSyncManager(appContext)

    val metadataExportManager = MetadataExportManager(appContext)

    /**
     * Called when server settings change to refresh the API client.
     */
    fun configureApi(serverUrl: String, apiKey: String) {
        romRepository.configureApi(serverUrl, apiKey)
    }
}
