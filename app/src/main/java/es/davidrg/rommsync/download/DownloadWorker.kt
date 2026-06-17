package es.davidrg.rommsync.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.remote.NetworkModule
import es.davidrg.rommsync.data.remote.RomMApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
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
        val serverUrl = inputData.getString(KEY_SERVER_URL) ?: return@withContext Result.failure()
        val apiKey = inputData.getString(KEY_API_KEY) ?: return@withContext Result.failure()

        if (romId < 0 || platformId < 0) return@withContext Result.failure()

        // Read ROMs root path from DataStore
        val dataStore = SettingsDataStore(applicationContext)
        val romsRootPath = dataStore.getRomsRootPathBlocking()

        // Ensure target platform directory exists
        PathMapper.ensurePlatformDir(romsRootPath, platformSlug)

        // Build API client for this download
        val apiService = NetworkModule.createApiService(serverUrl, apiKey)

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
        } catch (e: Exception) {
            Result.retry()
        }
    }

    /**
     * Builds progress Data with ALL metadata fields so that DownloadManager can
     * read rom info from WorkInfo.progress without relying on inputData.
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
    }

    /**
     * Streams a response body directly to disk, reporting byte-level progress.
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
    }

    /**
     * Extracts a mod_zip stream directly into the platform folder.
     * Used when RomM dynamically zips multi-file ROMs (Content-Length = -1).
     */
    private suspend fun extractZipStream(
        body: ResponseBody,
        romsRootPath: String,
        platformSlug: String,
    ) {
        val targetDir = PathMapper.getPlatformDir(romsRootPath, platformSlug)

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
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }

    companion object {
        const val KEY_ROM_ID = "rom_id"
        const val KEY_ROM_NAME = "rom_name"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_PLATFORM_ID = "platform_id"
        const val KEY_PLATFORM_SLUG = "platform_slug"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_PROGRESS = "progress"
        const val KEY_INDETERMINATE = "indeterminate"
        const val KEY_LOCAL_PATH = "local_path"

        const val BUFFER_SIZE = 8 * 1024 // 8KB
    }
}
