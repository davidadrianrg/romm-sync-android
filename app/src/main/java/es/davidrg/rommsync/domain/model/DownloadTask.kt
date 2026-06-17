package es.davidrg.rommsync.domain.model

/**
 * Represents a download task in the WorkManager queue.
 */
data class DownloadTask(
    val romId: Int,
    val romName: String,
    val fileName: String,
    val platformSlug: String,
    val workId: String,
    val progress: Int = 0,
    val isIndeterminate: Boolean = false,
    val isRunning: Boolean = false,
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false,
)
