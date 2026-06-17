package es.davidrg.rommsync.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.lifecycle.asFlow
import es.davidrg.rommsync.domain.model.DownloadTask
import es.davidrg.rommsync.domain.model.Rom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Manages the WorkManager download queue.
 *
 * Enqueues ROM downloads, observes their progress, and supports cancellation.
 * Download concurrency is controlled via WorkManager's unique work chains.
 */
class DownloadManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    /**
     * Enqueues a ROM download. For multi-file ROMs, only the primary file is enqueued;
     * the server will dynamically zip all files via mod_zip.
     */
    fun enqueueDownload(
        rom: Rom,
        serverUrl: String,
        apiKey: String,
    ): String {
        val inputData = Data.Builder()
            .putInt(DownloadWorker.KEY_ROM_ID, rom.id)
            .putString(DownloadWorker.KEY_ROM_NAME, rom.name)
            .putString(DownloadWorker.KEY_FILE_NAME, rom.fileName)
            .putInt(DownloadWorker.KEY_PLATFORM_ID, rom.platformId)
            .putString(DownloadWorker.KEY_PLATFORM_SLUG, rom.platformSlug)
            .putString(DownloadWorker.KEY_SERVER_URL, serverUrl)
            .putString(DownloadWorker.KEY_API_KEY, apiKey)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .addTag(TAG_DOWNLOAD)
            .addTag(workTagForRom(rom.id))
            .build()

        workManager.enqueue(request)
        return request.id.toString()
    }

    /**
     * Observes all active and completed downloads as a Flow of DownloadTask list.
     */
    fun observeDownloads(): Flow<List<DownloadTask>> {
        return workManager.getWorkInfosByTag(TAG_DOWNLOAD).asFlow().map { workInfos ->
            workInfos
                .filter { it.state != WorkInfo.State.CANCELLED }
                .map { it.toDownloadTask() }
                .sortedByDescending { it.isRunning }
        }
    }

    /**
     * Cancel a specific ROM download.
     */
    fun cancelDownload(romId: Int) {
        workManager.cancelAllWorkByTag(workTagForRom(romId))
    }

    /**
     * Cancel all downloads in the queue.
     */
    fun cancelAllDownloads() {
        workManager.cancelAllWorkByTag(TAG_DOWNLOAD)
    }

    private fun workTagForRom(romId: Int) = "rom_$romId"

    private fun WorkInfo.toDownloadTask(): DownloadTask {
        val progress = progress.getInt(DownloadWorker.KEY_PROGRESS, 0)
        val indeterminate = progress.getBoolean(DownloadWorker.KEY_INDETERMINATE, false)
        val romName = inputData.getString(DownloadWorker.KEY_ROM_NAME) ?: "Unknown"
        val fileName = inputData.getString(DownloadWorker.KEY_FILE_NAME) ?: ""
        val platformSlug = inputData.getString(DownloadWorker.KEY_PLATFORM_SLUG) ?: ""

        return DownloadTask(
            romId = inputData.getInt(DownloadWorker.KEY_ROM_ID, -1),
            romName = romName,
            fileName = fileName,
            platformSlug = platformSlug,
            workId = id.toString(),
            progress = progress,
            isIndeterminate = indeterminate,
            isRunning = state == WorkInfo.State.RUNNING,
            isCompleted = state == WorkInfo.State.SUCCEEDED,
            isFailed = state == WorkInfo.State.FAILED,
        )
    }

    companion object {
        private const val TAG_DOWNLOAD = "romm_download"
    }
}
