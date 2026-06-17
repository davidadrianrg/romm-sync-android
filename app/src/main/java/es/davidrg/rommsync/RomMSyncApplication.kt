package es.davidrg.rommsync

import android.app.Application
import androidx.work.Configuration
import es.davidrg.rommsync.data.AppContainer

class RomMSyncApplication : Application(), Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
