package es.davidrg.rommsync.data.metadata

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import es.davidrg.rommsync.data.local.RomSyncDatabase
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.remote.NetworkModule
import es.davidrg.rommsync.domain.model.Rom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Worker que descarga metadatos (covers, screenshots, videos, manuals) de RomM
 * y los escribe en la estructura de carpetas de ES-DE, además de actualizar
 * el gamelist.xml por plataforma.
 *
 * Estructura destino:
 * ```
 * <roms_root>/downloaded_media/<slug>/covers/<name>.jpg
 * <roms_root>/downloaded_media/<slug>/screenshots/<name>.jpg
 * <roms_root>/downloaded_media/<slug>/videos/<name>.mp4
 * <roms_root>/downloaded_media/<slug>/manuals/<name>.pdf
 * <esde_data_dir>/gamelists/<slug>/gamelist.xml
 * ```
 */
class MetadataExportWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val platformId = inputData.getInt(KEY_PLATFORM_ID, -1)
        val platformSlug = inputData.getString(KEY_PLATFORM_SLUG)
            ?: return@withContext Result.failure()

        if (platformId < 0) return@withContext Result.failure()

        val dataStore = SettingsDataStore(applicationContext)
        val serverUrl = dataStore.getServerUrlBlocking()
        val apiKey = dataStore.getApiKeyBlocking()
        val romsRootPath = dataStore.getRomsRootPathBlocking()
        val esdeDataDir = dataStore.getEsdeDataDirBlocking()
        val retroHraiMediaPath = dataStore.getRetroHraiMediaPathBlocking()

        if (apiKey.isEmpty() || serverUrl.isEmpty()) {
            return@withContext Result.failure()
        }

        // Flags: qué tipos de media descargar
        val exportCovers = inputData.getBoolean(KEY_COVERS, true)
        val exportScreenshots = inputData.getBoolean(KEY_SCREENSHOTS, true)
        val exportVideos = inputData.getBoolean(KEY_VIDEOS, true)
        val exportManuals = inputData.getBoolean(KEY_MANUALS, true)
        val exportGamelist = inputData.getBoolean(KEY_GAMELIST, true)
        val exportRetroHrai = inputData.getBoolean(KEY_RETROHRAI, false)

        createNotificationChannel()
        try {
            setForeground(createForegroundInfo(platformSlug, 0, 0))
        } catch (e: Exception) {
            Log.w(TAG, "Could not start foreground", e)
        }

        val api = NetworkModule.createApiService(serverUrl, apiKey)
        val httpClient = OkHttpClient.Builder().build()
        val db = RomSyncDatabase.getDatabase(applicationContext)

        // Paginación: obtener todos los ROMs de la plataforma
        val allRomEntries = mutableListOf<GamelistWriter.GameEntry>()
        var offset = 0
        val limit = 100
        var hasMore = true
        var mediaDownloaded = 0
        var mediaSkipped = 0

        while (hasMore) {
            val params = buildMap {
                put("platform_ids", platformId.toString())
                put("limit", limit.toString())
                put("offset", offset.toString())
                put("order_by", "name")
                put("order_dir", "asc")
            }

            val response = try {
                api.getRoms(params)
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching ROMs at offset $offset", e)
                return@withContext Result.retry()
            }

            if (response.items.isEmpty()) break

            for (romDto in response.items) {
                val rom = mapRomDtoToDomain(romDto, serverUrl)
                val mediaBaseName = (rom.fileNameNoExt ?: rom.fileName.substringBeforeLast("."))
                    .replace(Regex("[^a-zA-Z0-9._ -]"), "")
                    .trim()

                // Nombre limpio del juego para RetroHRAI
                val gameName = rom.name
                    .replace(Regex("[^a-zA-Z0-9._ -]"), "")
                    .trim()

                // Descargar cover a ES-DE + RetroHRAI
                if (exportCovers && rom.coverUrlLarge != null) {
                    val esdeFile = downloadToFile(
                        httpClient, apiKey, rom.coverUrlLarge,
                        getMediaDir(romsRootPath, platformSlug, "covers"),
                        "$mediaBaseName.jpg",
                    )
                    if (esdeFile != null) mediaDownloaded++ else mediaSkipped++

                    // Copiar a RetroHRAI con naming {gameName}_cover_0.jpg
                    if (exportRetroHrai && esdeFile != null) {
                        copyToRetroHrai(
                            esdeFile, retroHraiMediaPath, platformSlug, "covers",
                            "${gameName}_cover_0.jpg",
                        )
                    }
                }

                // Descargar screenshots a ES-DE + RetroHRAI
                if (exportScreenshots && rom.screenshots.isNotEmpty()) {
                    val firstScreenshot = rom.screenshots.first()
                    val esdeFile = downloadToFile(
                        httpClient, apiKey, firstScreenshot,
                        getMediaDir(romsRootPath, platformSlug, "screenshots"),
                        "$mediaBaseName.jpg",
                    )
                    if (esdeFile != null) mediaDownloaded++ else mediaSkipped++

                    if (exportRetroHrai && esdeFile != null) {
                        // Screenshot_0 + fanart_0 (primera screenshot como fanart)
                        copyToRetroHrai(
                            esdeFile, retroHraiMediaPath, platformSlug, "screenshots",
                            "${gameName}_screenshot_0.jpg",
                        )
                        copyToRetroHrai(
                            esdeFile, retroHraiMediaPath, platformSlug, "fanart",
                            "${gameName}_fanart_0.jpg",
                        )
                    }
                }

                // Descargar videos a ES-DE
                if (exportVideos && rom.videoPath != null) {
                    val esdeFile = downloadToFile(
                        httpClient, apiKey, rom.videoPath,
                        getMediaDir(romsRootPath, platformSlug, "videos"),
                        "$mediaBaseName.mp4",
                    )
                    if (esdeFile != null) mediaDownloaded++ else mediaSkipped++
                }

                // Descargar manuals a ES-DE
                if (exportManuals && rom.manualPath != null) {
                    val esdeFile = downloadToFile(
                        httpClient, apiKey, rom.manualPath,
                        getMediaDir(romsRootPath, platformSlug, "manuals"),
                        "$mediaBaseName.pdf",
                    )
                    if (esdeFile != null) mediaDownloaded++ else mediaSkipped++
                }

                // Crear entry para gamelist
                allRomEntries.add(
                    GamelistWriter.romToEntry(rom, romsRootPath, platformSlug, mediaBaseName)
                )

                // Actualizar notificación
                val progress = ((offset + allRomEntries.size) * 100 /
                        (response.total.coerceAtLeast(1))).coerceIn(0, 100)
                try {
                    setForeground(createForegroundInfo(platformSlug, progress, mediaDownloaded))
                } catch (_: Exception) {}
            }

            offset += limit
            hasMore = offset < response.total
        }

        // Escribir/actualizar gamelist.xml
        if (exportGamelist && allRomEntries.isNotEmpty()) {
            val gamelistDir = File("$esdeDataDir/gamelists/$platformSlug")
            gamelistDir.mkdirs()
            val gamelistFile = File(gamelistDir, "gamelist.xml")

            val existing = GamelistWriter.readGamelist(gamelistFile)
            val xml = GamelistWriter.mergeAndSerialize(existing, allRomEntries)
            gamelistFile.writeText(xml, Charsets.UTF_8)
            Log.i(TAG, "Gamelist written: ${gamelistFile.absolutePath} (${allRomEntries.size} entries)")
        }

        Result.success(workDataOf(
            KEY_MEDIA_DOWNLOADED to mediaDownloaded,
            KEY_MEDIA_SKIPPED to mediaSkipped,
            KEY_ENTRIES to allRomEntries.size,
        ))
    }

    /**
     * Descarga un fichero desde una URL con autenticación Bearer.
     * Omite la descarga si el fichero ya existe.
     * Devuelve el File descargado, o null si falló/skip.
     */
    private fun downloadToFile(
        client: OkHttpClient,
        apiKey: String,
        url: String,
        targetDir: File,
        fileName: String,
    ): File? {
        val targetFile = File(targetDir, fileName)
        if (targetFile.exists()) return targetFile // ya existe, lo reutilizamos

        targetDir.mkdirs()
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Failed to download $url: HTTP ${response.code}")
                    return null
                }
                response.body?.byteStream()?.use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return null
            }
            targetFile
        } catch (e: IOException) {
            Log.w(TAG, "IO error downloading $fileName", e)
            if (targetFile.exists()) targetFile.delete()
            null
        }
    }

    /**
     * Copia un archivo ya descargado a la estructura de RetroHRAI.
     * Estructura: {retroHraiMediaPath}/{slug}/{type}/{fileName}
     * Omite si el destino ya existe.
     */
    private fun copyToRetroHrai(
        source: File,
        retroHraiMediaPath: String,
        platformSlug: String,
        type: String,
        fileName: String,
    ) {
        val targetDir = File("$retroHraiMediaPath/$platformSlug/$type")
        targetDir.mkdirs()
        val target = File(targetDir, fileName)
        if (target.exists()) return
        try {
            source.copyTo(target, overwrite = false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to copy to RetroHRAI: $fileName", e)
        }
    }

    private fun getMediaDir(romsRoot: String, slug: String, type: String): File {
        return File("$romsRoot/downloaded_media/$slug/$type")
    }

    /**
     * Mapea RomDto a Rom de dominio inline (sin acceder al Repository
     * que requiere coroutine context distinto).
     */
    private fun mapRomDtoToDomain(
        dto: es.davidrg.rommsync.data.remote.dto.RomDto,
        serverUrl: String,
    ): Rom {
        val base = serverUrl.trimEnd('/') + "/"
        return Rom(
            id = dto.id,
            name = dto.name.ifBlank { dto.fileName },
            fileName = dto.fileName,
            fileSizeBytes = dto.fileSizeBytes,
            platformId = dto.platformId,
            platformSlug = dto.platformSlug ?: "",
            coverUrlSmall = dto.pathCoverSmall?.let { base + it.removePrefix("/") },
            coverUrlLarge = dto.pathCoverLarge?.let { base + it.removePrefix("/") },
            files = dto.files.map { es.davidrg.rommsync.domain.model.RomFile(it.filename, it.size) },
            isMulti = dto.multi || dto.hasMultipleFiles,
            revision = dto.revision,
            regions = dto.regions,
            languages = dto.languages,
            genres = dto.genres,
            summary = dto.summary,
            fileNameNoTags = dto.fileNameNoTags,
            fileNameNoExt = dto.fileNameNoExt,
            fileExtension = dto.fileExtension,
            igdbId = dto.igdbId,
            screenshots = dto.mergedScreenshots.map { base + it.removePrefix("/") },
            videoPath = dto.pathVideo?.let { base + it.removePrefix("/") },
            manualPath = dto.pathManual?.let { base + it.removePrefix("/") },
            igdbMetadata = dto.igdbMetadata?.let { meta ->
                es.davidrg.rommsync.domain.model.IgdbMetadata(
                    totalRating = meta.totalRating?.toDoubleOrNull(),
                    firstReleaseDate = meta.firstReleaseDate,
                    genres = meta.genres,
                    companies = meta.companies,
                    gameModes = meta.gameModes,
                    playerCount = meta.playerCount,
                )
            },
        )
    }

    private fun createForegroundInfo(slug: String, progress: Int, mediaCount: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Exportando metadatos: $slug")
            .setContentText("$mediaCount archivos descargados")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, progress == 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Exportación de metadatos",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progreso de exportación de metadatos a ES-DE" }
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val TAG = "MetadataExportWorker"
        const val WORK_NAME_PREFIX = "metadata_export_"

        const val KEY_PLATFORM_ID = "platform_id"
        const val KEY_PLATFORM_SLUG = "platform_slug"
        const val KEY_COVERS = "export_covers"
        const val KEY_SCREENSHOTS = "export_screenshots"
        const val KEY_VIDEOS = "export_videos"
        const val KEY_MANUALS = "export_manuals"
        const val KEY_GAMELIST = "export_gamelist"
        const val KEY_RETROHRAI = "export_retrohrai"

        const val KEY_MEDIA_DOWNLOADED = "media_downloaded"
        const val KEY_MEDIA_SKIPPED = "media_skipped"
        const val KEY_ENTRIES = "entries_count"

        private const val CHANNEL_ID = "metadata_export"
        private const val NOTIFICATION_ID = 3001
    }
}
