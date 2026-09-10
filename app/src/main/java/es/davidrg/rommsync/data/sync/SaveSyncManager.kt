package es.davidrg.rommsync.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import es.davidrg.rommsync.data.local.SettingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Gestor de sincronización de saves. Expone métodos para la UI:
 * - Disparar sync manual.
 * - Programar sync periódico.
 * - Observar el estado del worker activo.
 */
class SaveSyncManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    /**
     * Encola un ciclo de sync como trabajo único (evita duplicados si se
     * pulsa varias veces).
     */
    fun triggerSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SaveSyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            SaveSyncWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Resuelve un conflicto forzando la dirección elegida por el usuario.
     *
     * @param resolution "local" para subir la versión local sobrescribiendo
     *   el servidor, "server" para descargar la del servidor sobrescribiendo
     *   la local.
     */
    fun triggerConflictResolution(romId: Int, fileName: String, resolution: String) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<SaveSyncWorker>()
            .setConstraints(constraints)
            .setInputData(
                Data.Builder()
                    .putInt(SaveSyncWorker.KEY_CONFLICT_ROM_ID, romId)
                    .putString(SaveSyncWorker.KEY_CONFLICT_FILE_NAME, fileName)
                    .putString(SaveSyncWorker.KEY_CONFLICT_RESOLUTION, resolution)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            SaveSyncWorker.CONFLICT_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    /**
     * Programa un sync periódico. Si intervalMinutes es 0 o negativo, cancela
     * cualquier sync periódico existente.
     *
     * @param replace si es true, reemplaza el trabajo existente (para cuando el
     *   usuario cambia el intervalo). Si es false, usa KEEP (para restaurar al
     *   arrancar sin reiniciar el timer).
     */
    fun schedulePeriodicSync(intervalMinutes: Int, replace: Boolean = false) {
        if (intervalMinutes <= 0) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<SaveSyncWorker>(
            intervalMinutes.toLong(), TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Observa el estado del worker de sync para actualizar la UI.
     *
     * Cuando el ciclo termina con éxito, persiste también el JSON de
     * conflictos detectados para que la UI de resolución los cargue.
     */
    fun observeSyncState(): Flow<SyncState> {
        return workManager.getWorkInfosForUniqueWorkFlow(SaveSyncWorker.WORK_NAME).map { infos ->
            val info = infos.firstOrNull()
            when (info?.state) {
                WorkInfo.State.RUNNING -> SyncState.Running
                WorkInfo.State.SUCCEEDED -> {
                    val message = info.outputData.getString(SaveSyncWorker.KEY_MESSAGE) ?: ""
                    val conflictsJson = info.outputData.getString(SaveSyncWorker.KEY_CONFLICTS_JSON)
                    if (conflictsJson != null) {
                        kotlinx.coroutines.runBlocking {
                            SettingsDataStore(context).setLastSyncConflictsJson(conflictsJson)
                        }
                    }
                    val failedJson = info.outputData.getString(SaveSyncWorker.KEY_FAILED_JSON)
                    if (failedJson != null) {
                        kotlinx.coroutines.runBlocking {
                            SettingsDataStore(context).setLastSyncFailedJson(failedJson)
                        }
                    }
                    SyncState.Success(message)
                }
                WorkInfo.State.FAILED -> {
                    val message = info.outputData.getString(SaveSyncWorker.KEY_MESSAGE) ?: "Error"
                    SyncState.Failed(message)
                }
                WorkInfo.State.ENQUEUED -> SyncState.Pending
                else -> SyncState.Idle
            }
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "save_sync_periodic"
    }

    /**
     * Cancela todos los trabajos de sync (periódico, único y resolución de
     * conflictos). Se llama cuando el usuario desactiva el sync de saves.
     */
    fun cancelAllSyncWork() {
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(SaveSyncWorker.WORK_NAME)
        workManager.cancelUniqueWork(SaveSyncWorker.CONFLICT_WORK_NAME)
    }
}

sealed class SyncState {
    data object Idle : SyncState()
    data object Pending : SyncState()
    data object Running : SyncState()
    data class Success(val message: String) : SyncState()
    data class Failed(val message: String) : SyncState()
}
