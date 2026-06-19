package es.davidrg.rommsync.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Gestor de sincronización de saves. Expone métodos para la UI:
 * - Disparar sync manual.
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
}

sealed class SyncState {
    data object Idle : SyncState()
    data object Pending : SyncState()
    data object Running : SyncState()
    data class Success(val message: String) : SyncState()
    data class Failed(val message: String) : SyncState()
}
