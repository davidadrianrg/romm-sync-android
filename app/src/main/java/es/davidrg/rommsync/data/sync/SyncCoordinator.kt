package es.davidrg.rommsync.data.sync

import android.os.Build
import android.util.Log
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.local.dao.RomDao
import es.davidrg.rommsync.data.remote.NetworkModule
import es.davidrg.rommsync.data.remote.RomMApiService
import es.davidrg.rommsync.data.remote.dto.DeviceRegistrationRequest
import es.davidrg.rommsync.data.remote.dto.DevicePaths
import es.davidrg.rommsync.data.remote.dto.NegotiateRequest
import es.davidrg.rommsync.data.remote.dto.RomSaveInfo
import es.davidrg.rommsync.data.remote.dto.SaveFileInfo
import es.davidrg.rommsync.data.remote.dto.SessionCompleteRequest
import es.davidrg.rommsync.data.sync.platform.LocalSave
import es.davidrg.rommsync.data.sync.platform.RetroArchSaveHandler
import es.davidrg.rommsync.data.sync.platform.SaveHandler
import es.davidrg.rommsync.data.sync.platform.SaveHandlerRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Orquesta el ciclo completo de sincronización de saves con el servidor RomM:
 * 1. Asegura que el dispositivo está registrado.
 * 2. Escanea saves locales.
 * 3. Negocia con el servidor (qué subir, qué bajar, qué conflictos).
 * 4. Ejecuta las operaciones.
 * 5. Cierra la sesión.
 */
class SyncCoordinator(
    private val settingsDataStore: SettingsDataStore,
    private val romDao: RomDao,
    private val platformDao: es.davidrg.rommsync.data.local.dao.PlatformDao,
    private val cacheDir: File,
) {

    /**
     * Ejecuta un ciclo completo de sync. Devuelve un resumen del resultado.
     */
    suspend fun runSync(): SyncResult = withContext(Dispatchers.IO) {
        val serverUrl = settingsDataStore.getServerUrlBlocking()
        val apiKey = settingsDataStore.getApiKeyBlocking()
        val retroArchBase = settingsDataStore.getRetroArchBasePathBlocking()

        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            return@withContext SyncResult(error = "Servidor no configurado")
        }

        val api = NetworkModule.createApiService(serverUrl, apiKey)

        // 1. Asegurar registro del dispositivo
        val deviceId = ensureDeviceRegistered(api, retroArchBase)
            ?: return@withContext SyncResult(error = "No se pudo registrar el dispositivo")

        // 2. Escanear saves locales de ROMs descargados
        val downloadedRoms = romDao.getAllDownloadedRomsBlocking()
        if (downloadedRoms.isEmpty()) {
            return@withContext SyncResult(message = "No hay ROMs descargados para sincronizar")
        }

        // Cargar configuración de plataformas para resolver handlers
        val platformConfigs = platformDao.getAllPlatformsBlocking()
            .associateBy { it.slug }

        val localSavesMap = mutableMapOf<Int, List<LocalSave>>()
        val handlerByRom = mutableMapOf<Int, SaveHandler>()

        for (rom in downloadedRoms) {
            val config = platformConfigs[rom.platformSlug]
            val handler = SaveHandlerRegistry.getHandler(
                platformSlug = rom.platformSlug,
                emulatorId = config?.emulatorId,
            )
            handlerByRom[rom.romId] = handler

            val effectiveBasePath = config?.savesPathOverride?.takeIf { it.isNotBlank() }
                ?: SaveHandlerRegistry.getDefaultSavesPath(
                    emulatorId = config?.emulatorId
                        ?: SaveHandlerRegistry.getDefaultEmulator(rom.platformSlug).id,
                    platformSlug = rom.platformSlug,
                    retroArchBase = retroArchBase,
                )

            val saves = handler.findSaves(
                romId = rom.romId,
                romFileName = rom.fileName,
                platformSlug = rom.platformSlug,
                savesBasePath = effectiveBasePath,
            )
            if (saves.isNotEmpty()) {
                localSavesMap[rom.romId] = saves
            }
        }

        // 3. Negociar
        val romSaveInfos = localSavesMap.map { (romId, saves) ->
            RomSaveInfo(
                romId = romId,
                saves = saves.map { save ->
                    SaveFileInfo(
                        file = save.fileName,
                        mtime = formatIso8601(save.lastModified),
                        sha1 = save.sha1,
                    )
                },
            )
        }

        // Incluir ROMs descargados sin saves locales (para que el servidor
        // pueda enviar saves que existan en el servidor pero no en local).
        val romsWithoutLocalSaves = downloadedRoms
            .filter { it.romId !in localSavesMap }
            .map { RomSaveInfo(romId = it.romId, saves = emptyList()) }

        val negotiateRequest = NegotiateRequest(
            deviceId = deviceId,
            roms = romSaveInfos + romsWithoutLocalSaves,
        )

        val negotiateResponse = try {
            api.negotiateSync(negotiateRequest)
        } catch (e: Exception) {
            Log.w(TAG, "Negotiate failed", e)
            return@withContext SyncResult(error = "Error en negociación: ${e.message}")
        }

        // 4. Ejecutar operaciones
        var completed = 0
        var failed = 0
        val conflicts = mutableListOf<String>()

        for (op in negotiateResponse.operations) {
            when (op.type) {
                "upload" -> {
                    val save = localSavesMap[op.romId]?.find { it.fileName == op.file }
                    val handler = handlerByRom[op.romId]
                    if (save != null && handler != null) {
                        val ok = executeUpload(api, save, op.romId, handler)
                        if (ok) completed++ else failed++
                    } else {
                        failed++
                    }
                }
                "download" -> {
                    val rom = downloadedRoms.find { it.romId == op.romId }
                    val handler = handlerByRom[op.romId]
                    if (rom != null && op.source != null && handler != null) {
                        val config = platformConfigs[rom.platformSlug]
                        val effectiveBasePath = config?.savesPathOverride?.takeIf { it.isNotBlank() }
                            ?: SaveHandlerRegistry.getDefaultSavesPath(
                                emulatorId = config?.emulatorId
                                    ?: SaveHandlerRegistry.getDefaultEmulator(rom.platformSlug).id,
                                platformSlug = rom.platformSlug,
                                retroArchBase = retroArchBase,
                            )
                        val ok = executeDownload(
                            api = api,
                            sourceUrl = op.source,
                            rom = rom,
                            fileName = op.file,
                            savesBasePath = effectiveBasePath,
                            handler = handler,
                        )
                        if (ok) completed++ else failed++
                    } else {
                        failed++
                    }
                }
                "conflict" -> {
                    conflicts.add("${op.file} (rom_id=${op.romId})")
                    // Por ahora: no hacer nada, loggeamos.
                    // En fases futuras se muestra UI de resolución.
                    Log.w(TAG, "Conflicto sin resolver: ${op.file} para rom ${op.romId}")
                }
                "noop" -> { /* Nada que hacer */ }
            }
        }

        // 5. Cerrar sesión
        try {
            api.completeSession(
                sessionId = negotiateResponse.sessionId,
                request = SessionCompleteRequest(
                    operationsCompleted = completed,
                    operationsFailed = failed,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to complete session", e)
        }

        SyncResult(
            uploaded = completed,
            downloaded = negotiateResponse.operations.count { it.type == "download" && true },
            conflicts = conflicts.size,
            message = buildResultMessage(completed, failed, conflicts.size),
        )
    }

    private suspend fun ensureDeviceRegistered(api: RomMApiService, retroArchBase: String): Int? {
        val cached = settingsDataStore.getSyncDeviceIdBlocking()
        if (cached != null) return cached

        return try {
            val response = api.registerDevice(
                DeviceRegistrationRequest(
                    name = "${Build.MANUFACTURER} ${Build.MODEL}",
                    platform = "android",
                    hostname = Build.DEVICE,
                    paths = DevicePaths(
                        roms = settingsDataStore.getRomsRootPathBlocking(),
                        saves = "$retroArchBase/saves",
                        states = "$retroArchBase/states",
                    ),
                ),
            )
            settingsDataStore.setSyncDeviceId(response.id)
            response.id
        } catch (e: Exception) {
            Log.e(TAG, "Device registration failed", e)
            null
        }
    }

    private suspend fun executeUpload(api: RomMApiService, save: LocalSave, romId: Int, handler: SaveHandler): Boolean {
        return try {
            val fileToUpload = handler.prepareForUpload(save)
            val requestFile = fileToUpload.asRequestBody("application/octet-stream".toMediaType())
            val filePart = MultipartBody.Part.createFormData("file", save.fileName, requestFile)
            val romIdBody = romId.toString().toRequestBody("text/plain".toMediaType())
            api.uploadSave(filePart, romIdBody)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed for ${save.fileName}", e)
            false
        }
    }

    private suspend fun executeDownload(
        api: RomMApiService,
        sourceUrl: String,
        rom: es.davidrg.rommsync.data.local.entity.DownloadedRomEntity,
        fileName: String,
        savesBasePath: String,
        handler: SaveHandler,
    ): Boolean {
        return try {
            // Extraer saveId del path: /api/saves/{id}/content
            val saveId = sourceUrl.split("/").let { parts ->
                val idx = parts.indexOf("saves")
                if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1].toIntOrNull() else null
            } ?: return false

            val responseBody = api.downloadSave(saveId)
            val tempFile = File(cacheDir, "sync_dl_${rom.romId}_$fileName")
            FileOutputStream(tempFile).use { out ->
                responseBody.byteStream().use { input ->
                    input.copyTo(out)
                }
            }

            val ok = handler.extractDownload(
                tempFile = tempFile,
                romFileName = rom.fileName,
                platformSlug = rom.platformSlug,
                savesBasePath = savesBasePath,
                targetFileName = fileName,
            )
            tempFile.delete()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Download failed for $fileName (rom ${rom.romId})", e)
            false
        }
    }

    private fun buildResultMessage(completed: Int, failed: Int, conflicts: Int): String {
        val parts = mutableListOf<String>()
        if (completed > 0) parts.add("$completed completadas")
        if (failed > 0) parts.add("$failed fallidas")
        if (conflicts > 0) parts.add("$conflicts conflictos")
        return if (parts.isEmpty()) "Todo sincronizado" else parts.joinToString(", ")
    }

    companion object {
        private const val TAG = "SyncCoordinator"

        private fun formatIso8601(millis: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date(millis))
        }
    }
}

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val conflicts: Int = 0,
    val message: String? = null,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}
