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
import es.davidrg.rommsync.data.local.RomSyncDatabase
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.local.entity.DownloadedRomEntity
import es.davidrg.rommsync.data.remote.NetworkModule
import es.davidrg.rommsync.data.remote.RomMApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.util.Locale
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

        // ── Space check: fail fast if the target volume can't hold the ROM ──
        // The API doesn't expose file size upfront for all endpoints, so we
        // estimate from the Content-Length of a HEAD-ish first response below.
        // Here we only guard the resume case: if a partial already occupies
        // more than the free space, abort before wasting bytes.
        val targetFileForSpace = PathMapper.getRomFile(romsRootPath, platformSlug, fileName)
        val spaceCheck = hasEnoughSpace(targetFileForSpace.parentFile, neededBytes = -1L)
        if (spaceCheck == SpaceCheck.NO_STORAGE) {
            return@withContext Result.failure(workDataOf(
                KEY_ROM_NAME to romName,
                KEY_FILE_NAME to fileName,
                KEY_PLATFORM_SLUG to platformSlug,
                KEY_ERROR_MESSAGE to "No queda espacio en el almacenamiento",
            ))
        }

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
                    apiService, romsRootPath, fileName, romId, romName, platformSlug,
                    expectedHash = inputData.getString(KEY_FILE_HASH),
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
            if (runAttemptCount < 10) Result.retry() else Result.failure(workDataOf(
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
     *
     * On network failure the partial file is **always kept** (fresh and resume
     * mode alike) so the next attempt continues from the largest offset —
     * critical for multi-GB ROMs over unstable Wi-Fi.
     */
    private suspend fun performDownload(
        apiService: RomMApiService,
        romsRootPath: String,
        fileName: String,
        romId: Int,
        romName: String,
        platformSlug: String,
        expectedHash: String? = null,
    ): Result {
        val targetFile = PathMapper.getRomFile(romsRootPath, platformSlug, fileName)

        // Offset de reanudación: tamaño del parcial si existe y es plausible
        val partialBytes = if (targetFile.exists()) targetFile.length() else 0L
        val rangeHeader = if (partialBytes > 0L) "bytes=$partialBytes-" else null

        val response = apiService.downloadRom(romId, fileName, rangeHeader)
        val contentLength = response.body()?.contentLength() ?: -1L
        val isPartialResponse = response.code() == 206

        // ── Space check con tamaño conocido: aborta antes de escribir nada ──
        if (contentLength > 0L) {
            val alreadyOnDisk = if (isPartialResponse) partialBytes else 0L
            val missing = contentLength - alreadyOnDisk
            if (missing > 0L) {
                when (hasEnoughSpace(targetFile.parentFile, neededBytes = missing)) {
                    SpaceCheck.NO_STORAGE -> {
                        runCatching { response.body()?.close() }
                        return Result.failure(workDataOf(
                            KEY_ROM_NAME to romName,
                            KEY_FILE_NAME to fileName,
                            KEY_PLATFORM_SLUG to platformSlug,
                            KEY_ERROR_MESSAGE to "Espacio insuficiente: faltan ${formatBytes(missing)}",
                        ))
                    }
                    SpaceCheck.LOW_SPACE -> {
                        Log.w(TAG, "Low storage downloading '$romName' (needs ${formatBytes(missing)}) — proceeding")
                    }
                    SpaceCheck.OK -> {}
                }
            }
        }

        return try {
            when {
                response.code() == 416 -> {
                    // El local ya tiene todos los bytes que el servidor ofrece
                    Log.i(TAG, "Server says 416 — local file already complete ($partialBytes bytes)")
                }
                contentLength <= 0L && response.code() == 200 -> {
                    // mod_zip: Content-Length = -1, stream comprimido al vuelo
                    reportProgress(0, true, romId, romName, fileName, platformSlug)
                    extractZipStream(response.body()!!, romsRootPath, platformSlug)
                }
                else -> {
                    val body = response.body() ?: throw IOException("Respuesta sin cuerpo (HTTP ${response.code()})")
                    // Total esperado: bytes ya en disco + los que faltan (206)
                    // o Content-Length completo (200).
                    val offset = if (isPartialResponse) partialBytes else 0L
                    val totalBytes = if (isPartialResponse) partialBytes + contentLength else contentLength
                    if (isPartialResponse) {
                        Log.i(TAG, "Resuming '$romName' at $partialBytes bytes ($contentLength remaining)")
                    }
                    streamToDisk(body, targetFile, totalBytes, offset,
                        romId, romName, fileName, platformSlug)
                }
            }

            // ── Verificación de integridad: hash local vs hash del servidor ──
            // RomM expone el hash del fichero (MD5 o SHA-1 según su config).
            // Solo verificamos si el servidor lo conoce: detecta resumes
            // desalineados y transferencias corruptas antes de que el usuario
            // descubra el ROM roto dentro del emulador.
            var verificationFailure: Result? = null
            if (expectedHash != null) {
                val hashAlgo = detectHashAlgorithm(expectedHash)
                if (hashAlgo != null) {
                    reportProgress(
                        progress = 100, indeterminate = true,
                        romId = romId, romName = romName,
                        fileName = fileName, platformSlug = platformSlug,
                        downloadedBytes = targetFile.length(), totalBytes = 0L,
                        progressText = "Verificando integridad…",
                    )
                    val localHash = withContext(Dispatchers.IO) {
                        computeFileHash(targetFile, hashAlgo)
                    }
                    if (!localHash.equals(expectedHash, ignoreCase = true)) {
                        Log.e(TAG, "Hash mismatch for '$romName': expected=$expectedHash got=$localHash — deleting corrupt file")
                        targetFile.delete()
                        verificationFailure = Result.failure(workDataOf(
                            KEY_ROM_NAME to romName,
                            KEY_FILE_NAME to fileName,
                            KEY_PLATFORM_SLUG to platformSlug,
                            KEY_ERROR_MESSAGE to "Descarga corrupta (hash no coincide) — reintenta",
                        ))
                    } else {
                        Log.i(TAG, "Hash OK for '$romName' ($hashAlgo)")
                    }
                } else {
                    Log.w(TAG, "Unknown hash format '${expectedHash.take(8)}…' for '$romName' — skipping verification")
                }
            }

            if (verificationFailure != null) {
                verificationFailure
            } else {

            // Persist download record in Room so UI shows the checkmark
            val platformId = inputData.getInt(KEY_PLATFORM_ID, 0)
            val db = RomSyncDatabase.getDatabase(applicationContext)
            db.romDao().insertDownloadedRom(
                DownloadedRomEntity(
                    romId = romId,
                    name = romName,
                    fileName = fileName,
                    platformId = platformId,
                    platformSlug = platformSlug,
                    localPath = targetFile.absolutePath,
                    fileSizeBytes = targetFile.length(),
                ),
            )

            Result.success(workDataOf(
                KEY_ROM_ID to romId, KEY_ROM_NAME to romName,
                KEY_FILE_NAME to fileName, KEY_PLATFORM_SLUG to platformSlug,
                KEY_LOCAL_PATH to targetFile.absolutePath,
            ))
            }
        } finally {
            // Defensive close: streamToDisk / extractZipStream already close
            // the underlying InputStream via `use {}`, but ensure the body is
            // released even on early exceptions.
            runCatching { response.body()?.close() }
            runCatching { response.raw().close() }
        }
    }

    /**
     * Builds progress Data with ALL metadata fields so that DownloadManager can
     * read rom info from WorkInfo.progress without relying on inputData.
     * Also updates the foreground notification with current progress.
     *
     * @param downloadedBytes bytes ya escritos en disco (incluye offset de resume)
     * @param totalBytes tamaño total esperado del fichero
     * @param speedBps velocidad de transferencia en bytes/segundo
     */
    private suspend fun reportProgress(
        progress: Int,
        indeterminate: Boolean,
        romId: Int,
        romName: String,
        fileName: String,
        platformSlug: String,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        speedBps: Long = 0L,
        progressText: String? = null,
    ) {
        setProgress(workDataOf(
            KEY_PROGRESS to progress,
            KEY_INDETERMINATE to indeterminate,
            KEY_ROM_ID to romId,
            KEY_ROM_NAME to romName,
            KEY_FILE_NAME to fileName,
            KEY_PLATFORM_SLUG to platformSlug,
            KEY_DOWNLOADED_BYTES to downloadedBytes,
            KEY_TOTAL_BYTES to totalBytes,
            KEY_SPEED_BPS to speedBps,
        ))

        // Update foreground notification with progress
        try {
            val foreInfo = createForegroundInfo(romName, progress, indeterminate, downloadedBytes, totalBytes, speedBps)
            setForeground(foreInfo)
        } catch (e: Exception) {
            // Notification permission may have been revoked mid-download
        }
    }

    /**
     * Creates the [ForegroundInfo] with a notification showing download
     * progress, downloaded/total MB and transfer speed.
     */
    private fun createForegroundInfo(
        romName: String,
        progress: Int,
        indeterminate: Boolean,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        speedBps: Long = 0L,
    ): ForegroundInfo {
        val detail = buildString {
            if (totalBytes > 0L) {
                append(formatBytes(downloadedBytes))
                append(" / ")
                append(formatBytes(totalBytes))
                if (speedBps > 0L) {
                    append(" · ")
                    append(formatSpeed(speedBps))
                }
            }
        }.ifEmpty { "Descargando…" }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Descargando $romName")
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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

    /** 1536 B → "1.5 KB"; 5 GB ROMs → "4.7 GB" */
    private fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
            else -> "$bytes B"
        }
    }

    /** 1_500_000 B/s → "1.4 MB/s" */
    private fun formatSpeed(bytesPerSecond: Long): String {
        return formatBytes(bytesPerSecond) + "/s"
    }

    // ── Disk space check ────────────────────────────────────────────────

    /** Resultado de comprobar espacio libre en el volumen destino. */
    private enum class SpaceCheck { OK, LOW_SPACE, NO_STORAGE }

    /**
     * Detecta el algoritmo por la longitud del hash (MD5=32, SHA-1=40,
     * SHA-256=64 hex chars). RomM usa MD5 o SHA-1 según su config.
     */
    private fun detectHashAlgorithm(hash: String): String? = when (hash.length) {
        32 -> "MD5"
        40 -> "SHA-1"
        64 -> "SHA-256"
        else -> null
    }

    /** Hash del fichero completo con el algoritmo dado, en hex minúsculas. */
    private fun computeFileHash(file: File, algorithm: String): String {
        val digest = java.security.MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Comprueba el espacio disponible en el volumen que contiene [dir].
     *
     * @param neededBytes bytes requeridos; si es negativo solo comprueba que
     *   el volumen exista y sea escribible (OK si hay >0 bytes libres).
     * @return [SpaceCheck.NO_STORAGE] si no queda sitio o la ruta es inválida,
     *   [SpaceCheck.LOW_SPACE] si queda <5% libre además de lo requerido,
     *   [SpaceCheck.OK] en caso contrario.
     */
    private fun hasEnoughSpace(dir: File?, neededBytes: Long): SpaceCheck {
        val target = dir ?: return SpaceCheck.NO_STORAGE
        return try {
            val stat = android.os.StatFs(target.absolutePath)
            val free = stat.availableBytes
            when {
                free <= 0L -> SpaceCheck.NO_STORAGE
                neededBytes < 0L -> SpaceCheck.OK
                neededBytes > free -> SpaceCheck.NO_STORAGE
                free - neededBytes < stat.totalBytes / 20 -> SpaceCheck.LOW_SPACE // <5% margen
                else -> SpaceCheck.OK
            }
        } catch (_: Exception) {
            // Ruta inaccesible: no bloquear la descarga por un StatFs fallido
            SpaceCheck.OK
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
     * Progress reporting includes downloaded/total bytes and transfer speed,
     * shown both in the UI (WorkInfo.progress) and the foreground notification.
     *
     * On failure the partial file is **kept** in every mode so the next
     * attempt resumes from the largest offset reached.
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
        var input: java.io.InputStream? = null
        var output: java.io.FileOutputStream? = null
        try {
            input = body.byteStream()
            output = java.io.FileOutputStream(targetFile, offset > 0L)
            val buffer = ByteArray(64 * 1024)
            var bytesDownloaded = 0L
            var lastReportedProgress = -1
            var lastReportTime = System.currentTimeMillis()
            var lastReportBytes = 0L
            var speedBps = 0L

            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                bytesDownloaded = bytesDownloaded + read.toLong()

                val now = System.currentTimeMillis()
                if (totalBytes > 0L) {
                    val progress = (((offset + bytesDownloaded) * 100 / totalBytes)).toInt()
                    // Reportar como muy cada 2% o cada 800ms (para velocidad estable)
                    if (progress - lastReportedProgress >= 2 || now - lastReportTime >= 800) {
                        if (now - lastReportTime > 0) {
                            speedBps = (bytesDownloaded - lastReportBytes) * 1000 / (now - lastReportTime)
                        }
                        lastReportedProgress = progress
                        lastReportTime = now
                        lastReportBytes = bytesDownloaded
                        reportProgress(
                            progress, false, romId, romName, fileName, platformSlug,
                            downloadedBytes = offset + bytesDownloaded,
                            totalBytes = totalBytes,
                            speedBps = speedBps,
                        )
                    }
                }
            }
            // Reporte final (100%) con bytes totales
            reportProgress(
                100, false, romId, romName, fileName, platformSlug,
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                speedBps = speedBps,
            )
        } catch (e: Exception) {
            // Se conserva SIEMPRE el parcial para reanudar en el próximo intento
            throw e
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
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
                        java.io.FileOutputStream(outFile).use { out ->
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
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_SPEED_BPS = "speed_bps"
        const val KEY_FILE_HASH = "file_hash"

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
