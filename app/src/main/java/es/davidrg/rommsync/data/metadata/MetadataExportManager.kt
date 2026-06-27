package es.davidrg.rommsync.data.metadata

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Gestiona la exportación de metadatos a ES-DE. Expone métodos para la UI:
 * - Disparar exportación por plataforma.
 * - Observar el estado del worker activo.
 */
class MetadataExportManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    /**
     * Encola la exportación de metadatos para una plataforma.
     */
    fun triggerExport(
        platformId: Int,
        platformSlug: String,
        covers: Boolean = true,
        screenshots: Boolean = true,
        videos: Boolean = true,
        manuals: Boolean = true,
        gamelist: Boolean = true,
        retroHrai: Boolean = false,
    ) {
        val workName = "${MetadataExportWorker.WORK_NAME_PREFIX}$platformSlug"

        val inputData = Data.Builder()
            .putInt(MetadataExportWorker.KEY_PLATFORM_ID, platformId)
            .putString(MetadataExportWorker.KEY_PLATFORM_SLUG, platformSlug)
            .putBoolean(MetadataExportWorker.KEY_COVERS, covers)
            .putBoolean(MetadataExportWorker.KEY_SCREENSHOTS, screenshots)
            .putBoolean(MetadataExportWorker.KEY_VIDEOS, videos)
            .putBoolean(MetadataExportWorker.KEY_MANUALS, manuals)
            .putBoolean(MetadataExportWorker.KEY_GAMELIST, gamelist)
            .putBoolean(MetadataExportWorker.KEY_RETROHRAI, retroHrai)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<MetadataExportWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Observa el estado de exportación para una plataforma concreta.
     */
    fun observeExportState(platformSlug: String): Flow<ExportState> {
        val workName = "${MetadataExportWorker.WORK_NAME_PREFIX}$platformSlug"
        return workManager.getWorkInfosForUniqueWorkFlow(workName).map { infos ->
            val info = infos.firstOrNull()
            when (info?.state) {
                WorkInfo.State.RUNNING -> {
                    ExportState.Running(
                        mediaDownloaded = info.progress.getInt(
                            MetadataExportWorker.KEY_MEDIA_DOWNLOADED, 0
                        ),
                    )
                }
                WorkInfo.State.SUCCEEDED -> ExportState.Success(
                    mediaDownloaded = info.outputData.getInt(
                        MetadataExportWorker.KEY_MEDIA_DOWNLOADED, 0
                    ),
                    entriesCount = info.outputData.getInt(
                        MetadataExportWorker.KEY_ENTRIES, 0
                    ),
                )
                WorkInfo.State.FAILED -> ExportState.Failed("Error en exportación")
                WorkInfo.State.ENQUEUED -> ExportState.Pending
                else -> ExportState.Idle
            }
        }
    }
}

sealed class ExportState {
    data object Idle : ExportState()
    data object Pending : ExportState()
    data class Running(val mediaDownloaded: Int) : ExportState()
    data class Success(val mediaDownloaded: Int, val entriesCount: Int) : ExportState()
    data class Failed(val message: String) : ExportState()
}
