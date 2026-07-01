package es.davidrg.rommsync.data.sync

import android.os.Build
import android.util.Log
import es.davidrg.rommsync.data.local.SettingsDataStore
import es.davidrg.rommsync.data.local.dao.RomDao
import es.davidrg.rommsync.data.remote.NetworkModule
import es.davidrg.rommsync.data.remote.RomMApiService
import es.davidrg.rommsync.data.remote.dto.ClientSaveState
import es.davidrg.rommsync.data.remote.dto.DeviceRegistrationRequest
import es.davidrg.rommsync.data.remote.dto.NegotiateRequest
import es.davidrg.rommsync.data.remote.dto.SessionCompleteRequest
import es.davidrg.rommsync.data.sync.platform.LocalSave
import es.davidrg.rommsync.data.sync.platform.SaveHandler
import es.davidrg.rommsync.data.sync.platform.SaveHandlerRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
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

    suspend fun runSync(): SyncResult = withContext(Dispatchers.IO) {
        val serverUrl = settingsDataStore.getServerUrlBlocking()
        val apiKey = settingsDataStore.getApiKeyBlocking()
        val retroArchBase = settingsDataStore.getRetroArchBasePathBlocking()

        if (serverUrl.isEmpty() || apiKey.isEmpty()) {
            return@withContext SyncResult(error = "Servidor no configurado")
        }

        val api = NetworkModule.createApiService(serverUrl, apiKey)

        // 1. Asegurar registro del dispositivo
        val deviceId = ensureDeviceRegistered(api)
            ?: return@withContext SyncResult(error = "No se pudo registrar el dispositivo. Comprueba permisos y conexión.")

        // 2. Escanear saves locales de ROMs descargados
        val allDownloadedRoms = romDao.getAllDownloadedRomsBlocking()
        if (allDownloadedRoms.isEmpty()) {
            return@withContext SyncResult(message = "No hay ROMs descargados para sincronizar")
        }

        val downloadedRoms = allDownloadedRoms.filterNot { it.excludedFromSync }
        if (downloadedRoms.isEmpty()) {
            return@withContext SyncResult(message = "Todos los ROMs están excluidos de la sincronización")
        }

        val platformConfigs = platformDao.getAllPlatformsBlocking().associateBy { it.slug }
        val localSavesMap = mutableMapOf<Int, List<LocalSave>>()
        val handlerByRom = mutableMapOf<Int, SaveHandler>()

        for (rom in downloadedRoms) {
            val config = platformConfigs[rom.platformSlug]
            val handler = SaveHandlerRegistry.getHandler(
                platformSlug = rom.platformSlug,
                emulatorId = config?.emulatorId,
            )
            handlerByRom[rom.romId] = handler

            val effectiveBasePath = resolveSavesBasePath(rom, config, retroArchBase)
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

        // 3. Negociar con la API real de RomM
        val clientSaves = mutableListOf<ClientSaveState>()
        for ((romId, saves) in localSavesMap) {
            for (save in saves) {
                clientSaves.add(
                    ClientSaveState(
                        romId = romId,
                        fileName = save.fileName,
                        contentHash = save.sha1,
                        updatedAt = formatIso8601(save.lastModified),
                        fileSizeBytes = save.file.length().toInt(),
                    )
                )
            }
        }

        val negotiateRequest = NegotiateRequest(
            deviceId = deviceId,
            saves = clientSaves,
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
            when (op.action) {
                "upload" -> {
                    val save = localSavesMap[op.romId]?.find { it.fileName == op.fileName }
                    val handler = handlerByRom[op.romId]
                    if (save != null && handler != null) {
                        val ok = executeUpload(api, save, op.romId, deviceId, handler)
                        if (ok) completed++ else failed++
                    } else {
                        failed++
                    }
                }
                "download" -> {
                    val rom = downloadedRoms.find { it.romId == op.romId }
                    val handler = handlerByRom[op.romId]
                    if (rom != null && op.saveId != null && handler != null) {
                        val config = platformConfigs[rom.platformSlug]
                        val effectiveBasePath = resolveSavesBasePath(rom, config, retroArchBase)
                        val ok = executeDownload(
                            api = api,
                            saveId = op.saveId,
                            deviceId = deviceId,
                            rom = rom,
                            fileName = op.fileName,
                            savesBasePath = effectiveBasePath,
                            handler = handler,
                        )
                        if (ok) completed++ else failed++
                    } else {
                        failed++
                    }
                }
                "conflict" -> {
                    conflicts.add("${op.fileName} (rom_id=${op.romId})")
                    Log.w(TAG, "Conflicto sin resolver: ${op.fileName} para rom ${op.romId}: ${op.reason}")
                }
                "no_op" -> { /* Ya sincronizado */ }
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
            uploaded = negotiateResponse.operations.count { it.action == "upload" },
            downloaded = negotiateResponse.operations.count { it.action == "download" },
            conflicts = conflicts.size,
            message = buildResultMessage(completed, failed, conflicts.size),
        )
    }

    private fun resolveSavesBasePath(
        rom: es.davidrg.rommsync.data.local.entity.DownloadedRomEntity,
        config: es.davidrg.rommsync.data.local.entity.PlatformEntity?,
        retroArchBase: String,
    ): String {
        rom.savesPathOverride?.takeIf { it.isNotBlank() }?.let { return it }
        config?.savesPathOverride?.takeIf { it.isNotBlank() }?.let { return it }
        return SaveHandlerRegistry.getDefaultSavesPath(
            emulatorId = config?.emulatorId
                ?: SaveHandlerRegistry.getDefaultEmulator(rom.platformSlug).id,
            platformSlug = rom.platformSlug,
            retroArchBase = retroArchBase,
        )
    }

    private suspend fun ensureDeviceRegistered(api: RomMApiService): String? {
        val cached = settingsDataStore.getSyncDeviceIdStringBlocking()
        if (!cached.isNullOrBlank()) return cached

        return try {
            val response = api.registerDevice(
                DeviceRegistrationRequest(
                    name = "${Build.MANUFACTURER} ${Build.MODEL}",
                    platform = "android",
                    hostname = Build.DEVICE,
                ),
            )
            settingsDataStore.setSyncDeviceIdString(response.deviceId)
            response.deviceId
        } catch (e: retrofit2.HttpException) {
            Log.e(TAG, "Device registration HTTP ${e.code()}: ${e.message()}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Device registration failed", e)
            null
        }
    }

    private suspend fun executeUpload(
        api: RomMApiService,
        save: LocalSave,
        romId: Int,
        deviceId: String,
        handler: SaveHandler,
    ): Boolean {
        return try {
            val fileToUpload = handler.prepareForUpload(save)
            val requestFile = fileToUpload.asRequestBody("application/octet-stream".toMediaType())
            val filePart = MultipartBody.Part.createFormData("saveFile", save.fileName, requestFile)
            api.uploadSave(
                file = filePart,
                romId = romId,
                deviceId = deviceId,
                overwrite = true,
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Upload failed for ${save.fileName}", e)
            false
        }
    }

    private suspend fun executeDownload(
        api: RomMApiService,
        saveId: Int,
        deviceId: String,
        rom: es.davidrg.rommsync.data.local.entity.DownloadedRomEntity,
        fileName: String,
        savesBasePath: String,
        handler: SaveHandler,
    ): Boolean {
        return try {
            val responseBody = api.downloadSave(saveId, deviceId)
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
