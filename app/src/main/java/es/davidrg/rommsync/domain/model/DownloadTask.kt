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
    val errorMessage: String? = null,
    /** Bytes ya descargados (incluye tramos reanudados). */
    val downloadedBytes: Long = 0L,
    /** Tamaño total del fichero, si el servidor lo informó. */
    val totalBytes: Long = 0L,
    /** Velocidad de transferencia en bytes/segundo. */
    val speedBps: Long = 0L,
) {
    /** Texto listo para UI: "1.2 GB / 4.7 GB · 3.1 MB/s". Vacío si sin datos. */
    val byteDetail: String
        get() = buildString {
            if (totalBytes > 0L) {
                append(formatBytesForUi(downloadedBytes))
                append(" / ")
                append(formatBytesForUi(totalBytes))
                if (speedBps > 0L) {
                    append(" · ")
                    append(formatBytesForUi(speedBps))
                    append("/s")
                }
            }
        }
}

/** Formatea bytes de forma legible (KB/MB/GB). */
fun formatBytesForUi(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(java.util.Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}
