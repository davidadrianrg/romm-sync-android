package es.davidrg.rommsync.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import es.davidrg.rommsync.download.DownloadManager
import es.davidrg.rommsync.domain.model.DownloadTask
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class DownloadQueueViewModel(
    private val downloadManager: DownloadManager,
) : ViewModel() {

    val downloads: StateFlow<List<DownloadTask>> = downloadManager.observeDownloads()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    fun cancelDownload(romId: Int) {
        downloadManager.cancelDownload(romId)
    }

    fun cancelAll() {
        downloadManager.cancelAllDownloads()
    }

    /**
     * Prunes all SUCCEEDED and FAILED work from the WorkManager queue.
     * The downloads StateFlow will automatically update after prune.
     */
    fun clearCompleted() {
        downloadManager.pruneCompletedWork()
    }

    /**
     * Re-enqueues a failed download as a new work request.
     */
    fun retryDownload(task: DownloadTask) {
        downloadManager.retryDownload(
            romId = task.romId,
            romName = task.romName,
            fileName = task.fileName,
            platformSlug = task.platformSlug,
        )
    }
}
