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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * WorkManager CoroutineWorker that downloads a single ROM from the RomM server.
 *
 * Handles two streaming modes:
 * 1. **Normal download** (Content-Length > 0): streams bytes directly to disk
 *    with progress reporting. Supports auto-resume via HTTP `Range` header —
 *    if a partial file from a previous interrupted run already exists on disk,
 *    the worker asks the server for the remaining bytes (HTTP 206) and
 *    appends them. If the server returns 200 (no range support / file changed)
 *    the worker re-downloads from scratch.
 * 2. **mod_zip stream** (Content-Length = -1): RomM dynamically zips multi-file
 *    ROMs on the fly. The worker switches to indeterminate progress and extracts
 *    the zip stream directly into the platform folder.
 *
 * Concurrency (the user's "maxConcurrentDownloads" setting, 1-5) is enforced by
 * a process-wide [Semaphore] kept in the [companion object]. See
 * [syncConcurrencyLimit] for details.
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
        val maxConcurrent = dataStore.getMaxConcurrentDownloadsBlocking()

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

        // Make sure the global semaphore reflects the latest user setting,
        // then acquire a permit for the entire duration of the network call.
        syncConcurrencyLimit(maxConcurrent)

        return@withContext try {
            currentSemaphore.withPermit {
                performDownload(
                    apiService, romsRootPath, platformSlug, fileName,
                    romId, romName,
                )
            }
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
     * Performs the actual HTTP download with auto-resume support.
     *
     * Steps:
     * 1. If a partial file already exists on disk, send `Range: bytes=N-`.
     * 2. Inspect the HTTP status code:
     *    - **416 Range Not Satisfiable**: the local file is already complete.
     *    - **206 Partial Content**: append the missing bytes (resume).
     *    - **200 OK**: server ignored Range (or doesn't support it); re-download
     *      from scratch.
     * 3. If `Content-Length == -1` and the response is 200 (not 206), treat as
     *    mod_zip stream and extract on the fly.
     */
    private suspend fun performDownload(
        apiService: RomMApiService,
        romsRootPath: String,
        platformSlug: String,
        fileName: String,
        romId: Int,
        romName: String,
    ): Result {
        val targetFile = PathMapper.getRomFile(romsRootPath, platformSlug, fileName)

        // Detect a partial file from a previous interrupted attempt.
        val existingSize = if (targetFile.exists()) targetFile.length() else 0L
        val rangeHeader: String? = if (existingSize > 0L) "bytes=$existingSize-" else null
        if (existingSize > 0L) {
            Log.i(TAG, "Attempting resume of '$romName': $existingSize bytes already on disk")
        }

        val response: Response<ResponseBody> = try {
            apiService.downloadRom(romId, fileName, rangeHeader)
        } catch (e: Exception) {
            // Make sure any partial body is closed on failure.
            throw e
        }

        // Non-2xx responses.
        if (!response.isSuccessful) {
            // 416 Range Not Satisfiable → the local file already covers (or
            // exceeds) the full resource. Treat the download as complete.
            if (response.code() == 416 && existingSize > 0L) {
                Log.i(TAG, "'$romName' already complete (HTTP 416 on Range request)")
                response.errorBody()?.close()
                reportProgress(100, false, romId, romName, fileName, platformSlug)
                return Result.success(workDataOf(
                    KEY_ROM_ID to romId,
                    KEY_ROM_NAME to romName,
                    KEY_FILE_NAME to fileName,
                    KEY_PLATFORM_SLUG to platformSlug,
                    KEY_LOCAL_PATH to targetFile.absolutePath,
                ))
            }
            response.errorBody()?.close()
            throw HttpException(response)
        }

        val body: ResponseBody = response.body() ?: run {
            response.errorBody()?.close()
            throw IOException("Empty response body")
        }

        // 206 = server honoured our Range header → resume.
        // 200 = server ignored Range → fresh download.
        val isPartial = response.code() == 206
        val offset = if (isPartial) existingSize else 0L
        val contentLength = body.contentLength()

        // Edge case: server returned 200 OK with the full body but the local
        // file already has the same (or more) bytes → treat as complete.
        if (!isPartial && existingSize > 0L && contentLength > 0L && existingSize >= contentLength) {
            Log.i(TAG, "'$romName' already complete (local size ≥ Content-Length)")
            body.close()
            reportProgress(100, false, romId, romName, fileName, platformSlug)
            return Result.success(workDataOf(
                KEY_ROM_ID to romId,
                KEY_ROM_NAME to romName,
                KEY_FILE_NAME to fileName,
                KEY_PLATFORM_SLUG to platformSlug,
                KEY_LOCAL_PATH to targetFile.absolutePath,
            ))
        }

        return try {
            if (contentLength <= 0L && !isPartial) {
                // mod_zip: Content-Length = -1 → extract on the fly.
                // Resume is not supported for zip streams; delete any stale
                // partial file from a previous attempt to avoid corruption.
                if (targetFile.exists()) targetFile.delete()
                reportProgress(0, true, romId, romName, fileName, platformSlug)
                extractZipStream(body, romsRootPath, platformSlug)
            } else {
                // Normal: stream to disk with progress (and resume support).
                val totalBytes = offset + contentLength.coerceAtLeast(0L)
                streamToDisk(
                    body = body,
                    targetFile = targetFile,
                    totalBytes = totalBytes,
                    offset = offset,
                    romId = romId,
                    romName = romName,
                    fileName = fileName,
                    platformSlug = platformSlug,
                )
            }

            Result.success(workDataOf(
                KEY_ROM_ID to romId,
                KEY_ROM_NAME to romName,
                KEY_FILE_NAME to fileName,
                KEY_PLATFORM_SLUG to platformSlug,
                KEY_LOCAL_PATH to targetFile.absolutePath,
            ))
        } finally {
            // Defensive close: streamToDisk / extractZipStream already close
            // the underlying InputStream via `use {}`, but ensure the body is
            // released even on early exceptions.
            runCatching { body.close() }
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
     *
     * When [offset] > 0 (resume mode), the file is opened in **append** mode
     * and the offset is added to the byte count when computing progress so the
     * progress bar reflects the whole file, not just the resumed chunk.
     *
     * On failure:
     * - In resume mode the partial file is **kept** so the next attempt can
     *   continue from a larger offset.
     * - In fresh-download mode the partial file is **deleted** to avoid mixing
     *   half-written data with a future retry.
     */
    private suspend fun streamToDisk(
        body: ResponseBody,
        targetFile: File,
        totalBytes: Long,
        offset: Long,
        romId: Int,
        romName: String,
        fileName: String,
        platformSlug: String,
    ) {
        try {
            body.byteStream().use { input ->
                FileOutputStream(targetFile, append = offset > 0L).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesDownloaded = 0L

                    // Pre-report resume progress so the UI jumps to offset%.
                    var lastReportedProgress = if (offset > 0L && totalBytes > 0L) {
                        ((offset * 100) / totalBytes).toInt().also { p ->
                            reportProgress(p, false, romId, romName, fileName, platformSlug)
                        }
                    } else {
                        -1
                    }

                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break

                        output.write(buffer, 0, read)
                        bytesDownloaded += read

                        // Report progress every ~2% of the TOTAL file
                        val progress = if (totalBytes > 0L) {
                            (((offset + bytesDownloaded) * 100) / totalBytes).toInt()
                        } else 0

                        if (progress - lastReportedProgress >= 2 || progress >= 100) {
                            lastReportedProgress = progress
                            reportProgress(progress, false, romId, romName, fileName, platformSlug)
                        }
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            // When resuming, keep the partial file so the next attempt can
            // continue from where we left off. When starting from scratch,
            // delete the partial file to avoid corrupt half-written data.
            if (offset == 0L && targetFile.exists()) {
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

        // ── Concurrency limiter ──────────────────────────────────────────────
        //
        // WorkManager itself does NOT expose a "max concurrent downloads" knob:
        // it will happily spin up to ~16 parallel CoroutineWorkers from its
        // default executor. To honour the user-configured 1-5 limit we keep a
        // process-wide [Semaphore] in the companion object. Each worker calls
        // [currentSemaphore].withPermit { } around the actual network call so
        // that only `maxConcurrentDownloads` workers transfer data at the same
        // time; the rest suspend waiting for a permit.
        //
        // When the user changes the setting, [syncConcurrencyLimit] swaps in a
        // fresh [Semaphore] with the new permit count. Workers that already
        // hold permits from the previous instance release them on that same
        // instance (no longer referenced by anyone else); the old Semaphore is
        // then eligible for GC. During the brief transition window the actual
        // concurrency may temporarily reach (old_limit + new_limit), which is
        // acceptable for a soft limit that the user just changed.

        @Volatile
        private var currentLimit: Int = SettingsDataStore.DEFAULT_MAX_DOWNLOADS

        @Volatile
        private var currentSemaphore: Semaphore =
            Semaphore(SettingsDataStore.DEFAULT_MAX_DOWNLOADS)

        /**
         * Updates [currentSemaphore] to match [limit] if the user has changed
         * the setting since the last call. Safe to call from every worker on
         * every run.
         */
        @Synchronized
        private fun syncConcurrencyLimit(limit: Int) {
            val safeLimit = limit.coerceIn(1, 5)
            if (safeLimit != currentLimit) {
                currentSemaphore = Semaphore(safeLimit)
                currentLimit = safeLimit
                Log.i(TAG, "Concurrency limit updated to $safeLimit parallel download(s)")
            }
        }

        private const val TAG = "DownloadWorker"
        private const val NOTIFICATION_CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1001
    }
}
