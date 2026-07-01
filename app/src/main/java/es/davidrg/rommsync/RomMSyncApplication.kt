package es.davidrg.rommsync

import android.app.Application
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import es.davidrg.rommsync.data.AppContainer
import java.io.File

class RomMSyncApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        restorePeriodicSync()
    }

    /**
     * Si el usuario tenia configurada una sincronización periódica, se
     * re-registra al arrancar la app. WorkManager persiste los trabajos
     * periódicos entre reinicios del dispositivo, pero re-enqueue con
     * KEEP garantiza que siempre exista.
     */
    private fun restorePeriodicSync() {
        val interval = container.settingsDataStore.getSyncIntervalMinutesBlocking()
        if (interval > 0) {
            container.saveSyncManager.schedulePeriodicSync(interval)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    /**
     * Single ImageLoader for the whole app.
     *
     * Tuning:
     *  - memoryCache: 25 % of the app's available memory (Coil computes maxSize
     *    from the runtime when we pass the percentage).
     *  - diskCache:   100 MB at cacheDir/image_cache.
     *  - crossfade:   smooth fade-in for covers.
     *  - HARDWARE & ENABLED policies keep covers cached as long as possible.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "image_cache"))
                    .maxSizeBytes(100L * 1024L * 1024L) // 100 MB
                    .build()
            }
            .crossfade(true)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
}
