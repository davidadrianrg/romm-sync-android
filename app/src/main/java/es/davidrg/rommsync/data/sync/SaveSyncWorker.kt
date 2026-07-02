package es.davidrg.rommsync.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import es.davidrg.rommsync.data.local.RomSyncDatabase
import es.davidrg.rommsync.data.local.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager CoroutineWorker que ejecuta un ciclo de sincronización de saves.
 *
 * Puede ser disparado manualmente por el usuario desde la UI o programado
 * periódicamente (futuro).
 */
class SaveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val dataStore = SettingsDataStore(applicationContext)
        val database = RomSyncDatabase.getDatabase(applicationContext)

        // Foreground notification
        createNotificationChannel()
        try {
            setForeground(createForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground (notification permission denied?)", e)
        }

        val coordinator = SyncCoordinator(
            settingsDataStore = dataStore,
            romDao = database.romDao(),
            platformDao = database.platformDao(),
            cacheDir = applicationContext.cacheDir,
            syncedHashStore = SyncedHashStore(applicationContext),
        )

        val result = coordinator.runSync()

        if (result.isSuccess) {
            Log.i(TAG, "Sync completed: ${result.message}")
            Result.success(workDataOf(
                KEY_MESSAGE to (result.message ?: "Sincronización completada"),
                KEY_UPLOADED to result.uploaded,
                KEY_DOWNLOADED to result.downloaded,
                KEY_CONFLICTS to result.conflicts,
            ))
        } else {
            Log.w(TAG, "Sync failed: ${result.error}")
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_MESSAGE to (result.error ?: "Error desconocido")))
            }
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Sincronizando saves")
            .setContentText("Conectando con el servidor...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sincronización de saves",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progreso de sincronización de partidas guardadas"
            }
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val TAG = "SaveSyncWorker"
        const val WORK_NAME = "save_sync"
        const val KEY_MESSAGE = "sync_message"
        const val KEY_UPLOADED = "sync_uploaded"
        const val KEY_DOWNLOADED = "sync_downloaded"
        const val KEY_CONFLICTS = "sync_conflicts"

        private const val CHANNEL_ID = "save_sync"
        private const val NOTIFICATION_ID = 2001
    }
}
