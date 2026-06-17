package es.davidrg.rommsync.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import es.davidrg.rommsync.domain.model.DownloadTask
import es.davidrg.rommsync.domain.model.Rom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * Manages the WorkManager download queue.
 *
 * Enqueues ROM downloads, observes their progress, and supports cancellation.
 */
class DownloadManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun enqueueDownload(
        rom: Rom,
        serverUrl: String,
    ): String {
        val inputData = Data.Builder()
            .putInt(DownloadWorker.KEY_ROM_ID, rom.id)
            .putString(DownloadWorker.KEY_ROM_NAME, rom.name)
            .putString(DownloadWorker.KEY_FILE_NAME, rom.fileName)
            .putInt(DownloadWorker.KEY_PLATFORM_ID, rom.platformId)
            .putString(DownloadWorker.KEY_PLATFORM_SLUG, rom.platformSlug)
            .putString(DownloadWorker.KEY_SERVER_URL, serverUrl)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_DOWNLOAD)
            .addTag(workTagForRom(rom.id))
            .build()

        workManager.enqueue(request)
        return request.id.toString()
    }

    /**
     * Observes all active and completed downloads as a Flow of DownloadTask list.
     * Uses WorkManager 2.9+ native Flow API.
     */
    fun observeDownloads(): Flow<List<DownloadTask>> {
        return workManager.getWorkInfosByTagFlow(TAG_DOWNLOAD).map { workInfos ->
            workInfos
                .filter { it.state != WorkInfo.State.CANCELLED }
                .map { it.toDownloadTask() }
                .sortedByDescending { it.isRunning }
        }
    }

    fun cancelDownload(romId: Int) {
        workManager.cancelAllWorkByTag(workTagForRom(romId))
    }

    fun cancelAllDownloads() {
        workManager.cancelAllWorkByTag(TAG_DOWNLOAD)
    }

    /**
     * Prunes completed (SUCCEEDED, FAILED) work from the WorkManager queue.
     * After calling this, observeDownloads() will no longer emit those items.
     */
    fun pruneCompletedWork() {
        workManager.pruneWork()
    }

    /**
     * Re-enqueues a failed download as a new work request.
     * The worker reads server config from SettingsDataStore, so only rom metadata is needed.
     */
    fun retryDownload(
        romId: Int,
        romName: String,
        fileName: String,
        platformSlug: String,
    ): String {
        // Cancel the old failed work first so it gets cleaned up
        cancelDownload(romId)

        val inputData = Data.Builder()
            .putInt(DownloadWorker.KEY_ROM_ID, romId)
            .putString(DownloadWorker.KEY_ROM_NAME, romName)
            .putString(DownloadWorker.KEY_FILE_NAME, fileName)
            .putInt(DownloadWorker.KEY_PLATFORM_ID, 0)
            .putString(DownloadWorker.KEY_PLATFORM_SLUG, platformSlug)
            .build()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(inputData)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_DOWNLOAD)
            .addTag(workTagForRom(romId))
            .build()

        workManager.enqueue(request)
        return request.id.toString()
    }

    private fun workTagForRom(romId: Int) = "rom_$romId"

    /**
     * Converts a WorkInfo to a DownloadTask.
     * Reads metadata from progress Data (which includes rom info + progress updates).
     */
    private fun WorkInfo.toDownloadTask(): DownloadTask {
        val progressData = progress
        val outputData = outputData

        val romId = progressData.getInt(DownloadWorker.KEY_ROM_ID, -1)
        val romName = progressData.getString(DownloadWorker.KEY_ROM_NAME)
            ?: outputData.getString(DownloadWorker.KEY_ROM_NAME)
            ?: "Unknown"
        val fileName = progressData.getString(DownloadWorker.KEY_FILE_NAME)
            ?: outputData.getString(DownloadWorker.KEY_FILE_NAME)
            ?: ""
        val platformSlug = progressData.getString(DownloadWorker.KEY_PLATFORM_SLUG)
            ?: outputData.getString(DownloadWorker.KEY_PLATFORM_SLUG)
            ?: ""

        return DownloadTask(
            romId = romId,
            romName = romName,
            fileName = fileName,
            platformSlug = platformSlug,
            workId = id.toString(),
            progress = progressData.getInt(DownloadWorker.KEY_PROGRESS, 0),
            isIndeterminate = progressData.getBoolean(DownloadWorker.KEY_INDETERMINATE, false),
            isRunning = state == WorkInfo.State.RUNNING,
            isCompleted = state == WorkInfo.State.SUCCEEDED,
            isFailed = state == WorkInfo.State.FAILED,
            errorMessage = outputData.getString(DownloadWorker.KEY_ERROR_MESSAGE),
        )
    }

    companion object {
        private const val TAG_DOWNLOAD = "romm_download"
    }
}
