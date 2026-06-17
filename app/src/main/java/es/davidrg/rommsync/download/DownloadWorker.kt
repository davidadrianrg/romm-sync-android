package es.davidrg.rommsync.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.remote.NetworkModule
import es.davidrg.rommsync.data.remote.RomMApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * WorkManager CoroutineWorker that downloads a single ROM from the RomM server.
 *
 * Handles two streaming modes:
 * 1. **Normal download** (Content-Length > 0): streams bytes directly to disk
 *    with progress reporting.
 * 2. **mod_zip stream** (Content-Length = -1): RomM dynamically zips multi-file
 *    ROMs on the fly. The worker switches to indeterminate progress and extracts
 *    the zip stream directly into the platform folder.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val romId = inputData.getInt(KEY_ROM_ID, -1)
        val romName = inputData.getString(KEY_ROM_NAME) ?: return@withContext Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return@withContext Result.failure()
        val platformId = inputData.getInt(KEY_PLATFORM_ID, -1)
        val platformSlug = inputData.getString(KEY_PLATFORM_SLUG) ?: return@withContext Result.failure()

        if (romId < 0 || platformId < 0) return@withContext Result.failure()

        // Read configuration directly from storage (API key lives in
        // EncryptedSharedPreferences — never passed through WorkManager Data).
        val dataStore = SettingsDataStore(applicationContext)
        val romsRootPath = dataStore.getRomsRootPathBlocking()
        val apiKey = dataStore.getApiKeyBlocking()
        val serverUrl = dataStore.getServerUrlBlocking().ifEmpty {
            inputData.getString(KEY_SERVER_URL) ?: return@withContext Result.failure()
        }

        if (apiKey.isEmpty()) return@withContext Result.failure()

        // Ensure target platform directory exists
        PathMapper.ensurePlatformDir(romsRootPath, platformSlug)

        // Build API client for this download
        val apiService = NetworkModule.createApiService(serverUrl, apiKey)

        // Start foreground service notification (safe for Android 14+)
        createNotificationChannel()
        try {
            val foreInfo = createForegroundInfo(romName, 0, indeterminate = true)
            setForeground(foreInfo)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground service (notification permission denied?)", e)
        }

        reportProgress(0, false, romId, romName, fileName, platformSlug)

        return@withContext try {
            val response = apiService.downloadRom(romId, fileName)
            val contentLength = response.contentLength()

            if (contentLength <= 0L) {
                // mod_zip: Content-Length = -1 → extract on the fly
                reportProgress(0, true, romId, romName, fileName, platformSlug)
                extractZipStream(response, romsRootPath, platformSlug)
            } else {
                // Normal: stream to disk with progress
                streamToDisk(
                    response, romsRootPath, platformSlug, fileName, contentLength,
                    romId, romName,
                )
            }

            Result.success(workDataOf(
                KEY_ROM_ID to romId,
                KEY_ROM_NAME to romName,
                KEY_FILE_NAME to fileName,
                KEY_PLATFORM_SLUG to platformSlug,
                KEY_LOCAL_PATH to PathMapper.getRomFile(romsRootPath, platformSlug, fileName).absolutePath,
            ))
        } catch (e: HttpException) {
            when (e.code()) {
                401, 403 -> {
                    Log.w(TAG, "Auth error downloading '$romName': HTTP ${e.code()} — API key inválida")
                    Result.failure(workDataOf(
                        KEY_ROM_NAME to romName,
                        KEY_FILE_NAME to fileName,
                        KEY_PLATFORM_SLUG to platformSlug,
                        KEY_ERROR_MESSAGE to "Error de autenticación (HTTP ${e.code()})",
                    ))
                }
                404 -> {
                    Log.w(TAG, "ROM not found on server: '$romName' (HTTP 404)")
                    Result.failure(workDataOf(
                        KEY_ROM_NAME to romName,
                        KEY_FILE_NAME to fileName,
                        KEY_PLATFORM_SLUG to platformSlug,
                        KEY_ERROR_MESSAGE to "Archivo no encontrado en el servidor (404)",
                    ))
                }
                in 500..599 -> {
                    Log.w(TAG, "Server error HTTP ${e.code()} for '$romName', attempt $runAttemptCount")
                    if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(
                        KEY_ROM_NAME to romName,
                        KEY_FILE_NAME to fileName,
                        KEY_PLATFORM_SLUG to platformSlug,
                        KEY_ERROR_MESSAGE to "Error del servidor (HTTP ${e.code()})",
                    ))
                }
                else -> {
                    Log.w(TAG, "HTTP ${e.code()} for '$romName', attempt $runAttemptCount")
                    if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf(
                        KEY_ROM_NAME to romName,
                        KEY_FILE_NAME to fileName,
                        KEY_PLATFORM_SLUG to platformSlug,
                        KEY_ERROR_MESSAGE to "Error HTTP ${e.code()}",
                    ))
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "IO error downloading '$romName', attempt $runAttemptCount", e)
            if (runAttemptCount < 5) Result.retry() else Result.failure(workDataOf(
                KEY_ROM_NAME to romName,
                KEY_FILE_NAME to fileName,
                KEY_PLATFORM_SLUG to platformSlug,
                KEY_ERROR_MESSAGE to "Error de red: ${e.message ?: "conexión interrumpida"}",
            ))
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error downloading '$romName'", e)
            Result.failure(workDataOf(
                KEY_ROM_NAME to romName,
                KEY_FILE_NAME to fileName,
                KEY_PLATFORM_SLUG to platformSlug,
                KEY_ERROR_MESSAGE to "Error inesperado: ${e.message ?: "desconocido"}",
            ))
        }
    }

    /**
     * Builds progress Data with ALL metadata fields so that DownloadManager can
     * read rom info from WorkInfo.progress without relying on inputData.
     * Also updates the foreground notification with current progress.
     */
    private suspend fun reportProgress(
        progress: Int,
        indeterminate: Boolean,
        romId: Int,
        romName: String,
        fileName: String,
        platformSlug: String,
    ) {
        setProgress(workDataOf(
            KEY_PROGRESS to progress,
            KEY_INDETERMINATE to indeterminate,
            KEY_ROM_ID to romId,
            KEY_ROM_NAME to romName,
            KEY_FILE_NAME to fileName,
            KEY_PLATFORM_SLUG to platformSlug,
        ))

        // Update foreground notification with progress
        try {
            val foreInfo = createForegroundInfo(romName, progress, indeterminate)
            setForeground(foreInfo)
        } catch (e: Exception) {
            // Notification permission may have been revoked mid-download
        }
    }

    /**
     * Creates the [ForegroundInfo] with a notification showing download progress.
     */
    private fun createForegroundInfo(
        romName: String,
        progress: Int,
        indeterminate: Boolean,
    ): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Descargando $romName")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .apply {
                if (indeterminate || progress == 0) {
                    setProgress(0, 0, true) // indeterminate
                } else {
                    setProgress(100, progress, false)
                }
            }
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires explicit foreground service type
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Ensures the download notification channel exists (safe to call multiple times).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Descargas",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progreso de descarga de ROMs"
            }
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Streams a response body directly to disk, reporting byte-level progress.
     * On failure, deletes the partial file before re-throwing.
     */
    private suspend fun streamToDisk(
        body: ResponseBody,
        romsRootPath: String,
        platformSlug: String,
        fileName: String,
        totalBytes: Long,
        romId: Int,
        romName: String,
    ) {
        val targetFile = PathMapper.getRomFile(romsRootPath, platformSlug, fileName)

        try {
            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesDownloaded = 0L
                    var lastReportedProgress = -1

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        // Report progress every ~2%
                        val progress = ((bytesDownloaded * 100) / totalBytes).toInt()
                        if (progress - lastReportedProgress >= 2 || progress >= 100) {
                            lastReportedProgress = progress
                            reportProgress(progress, false, romId, romName, fileName, platformSlug)
                        }
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            // Clean up partial file on failure
            if (targetFile.exists()) {
                targetFile.delete()
            }
            throw e
        }
    }

    /**
     * Extracts a mod_zip stream directly into the platform folder.
     * Tracks created files and deletes them all if extraction fails.
     * Used when RomM dynamically zips multi-file ROMs (Content-Length = -1).
     */
    private suspend fun extractZipStream(
        body: ResponseBody,
        romsRootPath: String,
        platformSlug: String,
    ) {
        val targetDir = PathMapper.getPlatformDir(romsRootPath, platformSlug)
        val createdFiles = mutableListOf<File>()

        try {
            ZipInputStream(body.byteStream()).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val outFile = File(targetDir, entry.name)

                    // Security: prevent path traversal
                    if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                val read = zipIn.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                            }
                        }
                        createdFiles.add(outFile)
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }
        } catch (e: Exception) {
            // Clean up all files created so far
            for (file in createdFiles) {
                if (file.exists()) {
                    file.delete()
                }
            }
            throw e
        }
    }

    companion object {
        const val KEY_ROM_ID = "rom_id"
        const val KEY_ROM_NAME = "rom_name"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_PLATFORM_ID = "platform_id"
        const val KEY_PLATFORM_SLUG = "platform_slug"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_PROGRESS = "progress"
        const val KEY_INDETERMINATE = "indeterminate"
        const val KEY_LOCAL_PATH = "local_path"
        const val KEY_ERROR_MESSAGE = "error_message"

        const val BUFFER_SIZE = 64 * 1024 // 64KB

        private const val TAG = "DownloadWorker"
        private const val NOTIFICATION_CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
    }
}