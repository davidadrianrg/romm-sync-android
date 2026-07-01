package es.davidrg.rommsync.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
     */
    fun observeSyncState(): Flow<SyncState> {
        return workManager.getWorkInfosForUniqueWorkFlow(SaveSyncWorker.WORK_NAME).map { infos ->
            val info = infos.firstOrNull()
            when (info?.state) {
                WorkInfo.State.RUNNING -> SyncState.Running
                WorkInfo.State.SUCCEEDED -> {
                    val message = info.outputData.getString(SaveSyncWorker.KEY_MESSAGE) ?: ""
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
}

sealed class SyncState {
    data object Idle : SyncState()
    data object Pending : SyncState()
    data object Running : SyncState()
    data class Success(val message: String) : SyncState()
    data class Failed(val message: String) : SyncState()
}
