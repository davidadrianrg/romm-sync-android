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

        // Modo resolución de conflicto único (disparado desde la UI de conflictos)
        val conflictRomId = inputData.getInt(KEY_CONFLICT_ROM_ID, -1)
        val conflictFileName = inputData.getString(KEY_CONFLICT_FILE_NAME)
        val conflictResolution = inputData.getString(KEY_CONFLICT_RESOLUTION)
        if (conflictRomId >= 0 && !conflictFileName.isNullOrBlank() && !conflictResolution.isNullOrBlank()) {
            val result = coordinator.runConflictResolution(conflictRomId, conflictFileName, conflictResolution)
            return@withContext if (result.isSuccess) {
                Result.success(workDataOf(KEY_MESSAGE to (result.message ?: "Conflicto resuelto")))
            } else {
                if (runAttemptCount < 3) Result.retry()
                else Result.failure(workDataOf(KEY_MESSAGE to (result.error ?: "Error desconocido")))
            }
        }

        val result = coordinator.runSync()

        if (result.isSuccess) {
            Log.i(TAG, "Sync completed: ${result.message}")
            notifySyncResult(success = true, message = result.message ?: "Sincronización completada", conflicts = result.conflicts)
            Result.success(workDataOf(
                KEY_MESSAGE to (result.message ?: "Sincronización completada"),
                KEY_UPLOADED to result.uploaded,
                KEY_DOWNLOADED to result.downloaded,
                KEY_CONFLICTS to result.conflicts,
                KEY_CONFLICTS_JSON to serializeConflicts(result.conflictDetails),
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

    /**
     * Notifica el resultado final del sync cuando la app está en segundo
     * plano. Solo si hubo actividad (uploads/downloads/conflictos) o error:
     * un "todo al día" silencioso no merece interrumpir.
     */
    private fun notifySyncResult(success: Boolean, message: String, conflicts: Int) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        val quiet = success && message.contains("nada que sincronizar", ignoreCase = true)
        if (quiet) return

        val title = when {
            !success -> "Sync de saves falló"
            conflicts > 0 -> "Sync terminó con $conflicts conflicto(s)"
            else -> "Saves sincronizados"
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(SYNC_RESULT_NOTIFICATION_ID, notification) }
    }

    companion object {
        const val TAG = "SaveSyncWorker"
        const val WORK_NAME = "save_sync"
        const val CONFLICT_WORK_NAME = "save_sync_conflict"
        const val KEY_MESSAGE = "sync_message"
        private const val SYNC_RESULT_NOTIFICATION_ID = 4202
        const val KEY_UPLOADED = "sync_uploaded"
        const val KEY_DOWNLOADED = "sync_downloaded"
        const val KEY_CONFLICTS = "sync_conflicts"
        const val KEY_CONFLICTS_JSON = "sync_conflicts_json"
        const val KEY_CONFLICT_ROM_ID = "conflict_rom_id"
        const val KEY_CONFLICT_FILE_NAME = "conflict_file_name"
        const val KEY_CONFLICT_RESOLUTION = "conflict_resolution"

        private const val CHANNEL_ID = "save_sync"
        private const val NOTIFICATION_ID = 2001

        /** Adaptador Moshi para serializar conflictos en el WorkInfo output. */
        private val conflictsAdapter by lazy {
            val moshi = com.squareup.moshi.Moshi.Builder()
                .add(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
                .build()
            moshi.adapter<List<ConflictInfo>>(
                com.squareup.moshi.Types.newParameterizedType(
                    List::class.java, ConflictInfo::class.java,
                ),
            )
        }

        private fun serializeConflicts(conflicts: List<ConflictInfo>): String = try {
            conflictsAdapter.toJson(conflicts)
        } catch (_: Exception) {
            "[]"
        }
    }
}
