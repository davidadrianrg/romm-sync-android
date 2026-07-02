package es.davidrg.rommsync.data.repository

import es.davidrg.rommsync.data.local.dao.PlatformDao
import es.davidrg.rommsync.data.local.dao.RomDao
import es.davidrg.rommsync.data.local.entity.DownloadedRomEntity
import es.davidrg.rommsync.data.local.entity.PlatformEntity
import es.davidrg.rommsync.data.remote.NetworkModule
import es.davidrg.rommsync.data.remote.RomMApiService
import es.davidrg.rommsync.data.remote.dto.RomDto
import es.davidrg.rommsync.domain.model.ApiResult
import es.davidrg.rommsync.domain.model.ErrorKind
import es.davidrg.rommsync.domain.model.Platform
import es.davidrg.rommsync.domain.model.Rom
import es.davidrg.rommsync.domain.model.RomFile
import es.davidrg.rommsync.download.PathMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import retrofit2.HttpException

/**
 * Central repository for all RomM data operations.
 * Bridges the remote API (Retrofit), local cache (Room), and domain models.
 */
class RomRepository(
    private val platformDao: PlatformDao,
    private val romDao: RomDao,
) {

    private var apiService: RomMApiService? = null
    private var currentServerUrl: String = ""
    private var currentApiKey: String = ""

    companion object {
        private const val SCAN_PAGE_SIZE = 100
    }

    fun configureApi(serverUrl: String, apiKey: String) {
        if (serverUrl != currentServerUrl || apiKey != currentApiKey) {
            currentServerUrl = serverUrl
            currentApiKey = apiKey
            apiService = NetworkModule.createApiService(serverUrl, apiKey)
        }
    }

    fun getServerUrl(): String = currentServerUrl.trimEnd('/')

    private fun api(): RomMApiService =
        apiService ?: throw IllegalStateException("API not configured. Call configureApi() first.")

    // ── Platforms ──────────────────────────────────────────────────────

    /**
     * Fetch platforms from server, cache to Room, return domain models.
     *
     * Usa [PlatformDao.upsertPreservingVisibility] en vez de REPLACE para no
     * sobrescribir la preferencia de visibilidad del usuario en cada refresh.
     */
    suspend fun fetchAndCachePlatforms(): ApiResult<Unit> {
        return try {
            val platforms = api().getPlatforms()
            platforms.forEach { dto ->
                platformDao.upsertPreservingVisibility(
                    id = dto.id,
                    slug = dto.slug,
                    name = dto.displayName ?: dto.name,
                    romCount = dto.romCount,
                    aspectRatio = dto.aspectRatio,
                )
            }
            ApiResult.Success(Unit)
        } catch (e: HttpException) {
            val kind = when (e.code()) {
                401, 403 -> ErrorKind.AUTH
                404 -> ErrorKind.NOT_FOUND
                in 500..599 -> ErrorKind.SERVER
                else -> ErrorKind.UNKNOWN
            }
            ApiResult.Error(kind, "HTTP ${e.code()}: ${e.message()}")
        } catch (e: IOException) {
            ApiResult.Error(ErrorKind.NETWORK, e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error(ErrorKind.UNKNOWN, e.message ?: "Unknown error")
        }
    }

    /** Get cached platforms as a reactive Flow. */
    fun getCachedPlatforms(): Flow<List<Platform>> =
        platformDao.getAllPlatforms().map { entities -> entities.map { it.toDomain() } }

    /** Get only visible platforms. */
    fun getVisiblePlatforms(): Flow<List<Platform>> =
        platformDao.getVisiblePlatforms().map { entities -> entities.map { it.toDomain() } }

    suspend fun updatePlatformVisibility(id: Int, visible: Boolean) {
        platformDao.updateVisibility(id, visible)
    }

    suspend fun updatePlatformEmulator(id: Int, emulatorId: String?) {
        platformDao.updateEmulator(id, emulatorId)
    }

    suspend fun updatePlatformSavesPath(id: Int, path: String?) {
        platformDao.updateSavesPathOverride(id, path)
    }

    // ── ROMs ───────────────────────────────────────────────────────────

    /**
     * Fetch paginated ROMs for a platform from the server.
     * Uses platform_ids (plural) + offset pagination per RomM API spec.
     * Optional search term triggers server-side full-text search across ALL ROMs.
     * Optional genre/region filters are applied server-side via the RomM API.
     */
    suspend fun fetchRoms(
        platformId: Int,
        limit: Int = 50,
        offset: Int = 0,
        search: String? = null,
        genre: String? = null,
        region: String? = null,
    ): ApiResult<List<Rom>> {
        return try {
            val params = buildMap {
                put("platform_ids", platformId.toString())
                put("limit", limit.toString())
                put("offset", offset.toString())
                put("order_by", "name")
                put("order_dir", "asc")
                if (!search.isNullOrBlank()) {
                    put("search_term", search.trim())
                }
                if (!genre.isNullOrBlank()) {
                    put("genre", genre.trim())
                }
                if (!region.isNullOrBlank()) {
                    put("region", region.trim())
                }
            }
            val response = api().getRoms(params)
            ApiResult.Success(response.items.map { it.toDomain() })
        } catch (e: HttpException) {
            val kind = when (e.code()) {
                401, 403 -> ErrorKind.AUTH
                404 -> ErrorKind.NOT_FOUND
                in 500..599 -> ErrorKind.SERVER
                else -> ErrorKind.UNKNOWN
            }
            ApiResult.Error(kind, "HTTP ${e.code()}: ${e.message()}")
        } catch (e: IOException) {
            ApiResult.Error(ErrorKind.NETWORK, e.message ?: "Network error")
        } catch (e: Exception) {
            ApiResult.Error(ErrorKind.UNKNOWN, e.message ?: "Unknown error")
        }
    }

    /** Reactive set of all downloaded ROM IDs for quick UI status lookup. */
    fun getDownloadedRomIds(): Flow<Set<Int>> =
        romDao.getAllDownloadedRomIds().map { it.toSet() }

    fun getDownloadedRomsForPlatform(platformId: Int): Flow<List<DownloadedRomEntity>> =
        romDao.getDownloadedRomsForPlatform(platformId)

    suspend fun markRomDownloaded(
        romId: Int,
        name: String,
        fileName: String,
        platformId: Int,
        platformSlug: String,
        localPath: String,
        fileSizeBytes: Long,
    ) {
        romDao.insertDownloadedRom(
            DownloadedRomEntity(
                romId = romId,
                name = name,
                fileName = fileName,
                platformId = platformId,
                platformSlug = platformSlug,
                localPath = localPath,
                fileSizeBytes = fileSizeBytes,
            )
        )
    }

    suspend fun removeDownloadedRom(romId: Int) {
        romDao.deleteDownloadedRom(romId)
    }

    /**
     * Elimina una ROM descargada del dispositivo: borra el archivo (o la
     * carpeta de un ROM multi-archivo) del disco y elimina el registro de
     * Room para que la app la marque de nuevo como no descargada.
     *
     * Devuelve true si el archivo se borró correctamente (o no existía).
     */
    suspend fun deleteDownloadedRom(romId: Int): Boolean = withContext(Dispatchers.IO) {
        val entity = romDao.getDownloadedRom(romId)
        var fileDeleted = true
        if (entity != null && entity.localPath.isNotBlank()) {
            val file = File(entity.localPath)
            if (file.exists()) {
                fileDeleted = file.deleteRecursively()
            }
        }
        romDao.deleteDownloadedRom(romId)
        fileDeleted
    }

    suspend fun isRomDownloaded(romId: Int): Boolean = romDao.isDownloaded(romId)

    // ── Escaneo de biblioteca local ─────────────────────────────────────

    /**
     * Escanea la biblioteca ya presente en disco y marca como descargados los
     * ROMs cuyos ficheros existan en la ruta esperada
     * (`{romsRootPath}/{platformSlug}/{fileName}`).
     *
     * Útil cuando el usuario ha copiado los juegos manualmente desde el PC en
     * vez de descargarlos desde RomM: recorre todas las plataformas, pagina sus
     * ROMs desde el servidor y comprueba la existencia del fichero local.
     *
     * @param romsRootPath ruta raíz de ROMs configurada.
     * @param onProgress callback opcional con el nombre de la plataforma en curso.
     * @return [ScanResult] con el número de juegos detectados y comprobados.
     */
    suspend fun scanDownloadedLibrary(
        romsRootPath: String,
        onProgress: (platformName: String) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        val platforms = platformDao.getAllPlatformsBlocking()
        if (platforms.isEmpty()) {
            return@withContext ScanResult(error = "No hay plataformas. Sincroniza el servidor primero.")
        }

        val alreadyDownloaded = romDao.getAllDownloadedRoms().map { it.romId }.toMutableSet()
        var detected = 0
        var scanned = 0

        try {
            for (platform in platforms) {
                onProgress(platform.name)
                // Si la carpeta de la plataforma no existe, saltamos su escaneo.
                val platformDir = PathMapper.getPlatformDir(romsRootPath, platform.slug)
                if (!platformDir.isDirectory) continue

                var offset = 0
                while (true) {
                    val result = fetchRoms(platform.id, limit = SCAN_PAGE_SIZE, offset = offset)
                    val roms = (result as? ApiResult.Success)?.data ?: break
                    if (roms.isEmpty()) break

                    for (rom in roms) {
                        scanned++
                        if (rom.id in alreadyDownloaded) continue
                        val localFile = findRomFileOnDisk(romsRootPath, rom) ?: continue
                        romDao.insertDownloadedRom(
                            DownloadedRomEntity(
                                romId = rom.id,
                                name = rom.name,
                                fileName = rom.fileName,
                                platformId = rom.platformId,
                                platformSlug = rom.platformSlug,
                                localPath = localFile.absolutePath,
                                fileSizeBytes = if (localFile.isFile) localFile.length() else 0L,
                            )
                        )
                        alreadyDownloaded.add(rom.id)
                        detected++
                    }

                    offset += roms.size
                    if (roms.size < SCAN_PAGE_SIZE) break
                }
            }
            ScanResult(detected = detected, scanned = scanned)
        } catch (e: Exception) {
            ScanResult(detected = detected, scanned = scanned, error = e.message ?: "Error durante el escaneo")
        }
    }

    /**
     * Busca en la carpeta de la plataforma un fichero (o carpeta, para ROMs
     * multi-archivo) que coincida con el ROM del servidor.
     *
     * Criterios de match (en orden de prioridad, el primero que acierte gana):
     * 1. Nombre de fichero exacto (case-insensitive): rom.fileName
     * 2. Nombre sin tags (region/revision): rom.fileNameNoTags + extensión
     * 3. Nombre sin extensión: rom.fileNameNoExt + cualquier extensión válida
     * 4. Nombre base del fichero local coincide con fileNameNoExt (sin tags ni ext)
     */
    private fun findRomFileOnDisk(romsRootPath: String, rom: Rom): File? {
        val platformDir = PathMapper.getPlatformDir(romsRootPath, rom.platformSlug)
        if (!platformDir.isDirectory) return null
        val entries = platformDir.listFiles() ?: return null
        if (entries.isEmpty()) return null

        // 1. Match exacto por fileName
        entries.firstOrNull { it.name.equals(rom.fileName, ignoreCase = true) }
            ?.let { return it }

        // 2. Match por nombre sin tags + extensión original
        val noTags = rom.fileNameNoTags
        val ext = rom.fileExtension
        if (!noTags.isNullOrBlank() && !ext.isNullOrBlank()) {
            val candidate = "$noTags.$ext"
            entries.firstOrNull { it.name.equals(candidate, ignoreCase = true) }
                ?.let { return it }
        }

        // 3. Match por fileNameNoExt con cualquier extensión del mismo tipo
        val noExt = rom.fileNameNoExt
        if (!noExt.isNullOrBlank()) {
            entries.firstOrNull { file ->
                file.nameWithoutExtension.equals(noExt, ignoreCase = true)
            }?.let { return it }
        }

        // 4. Match por nombre sin tags ni extensión contra el nombre base local
        if (!noTags.isNullOrBlank()) {
            val noTagsNoExt = noTags.substringBeforeLast('.').ifBlank { noTags }
            entries.firstOrNull { file ->
                file.nameWithoutExtension.equals(noTagsNoExt, ignoreCase = true)
            }?.let { return it }
        }

        return null
    }

    // ── Configuración de sync por juego ─────────────────────────────────

    /** Observa el registro de descarga de una ROM (incluye config de sync). */
    fun observeDownloadedRom(romId: Int): Flow<DownloadedRomEntity?> =
        romDao.observeDownloadedRom(romId)

    /** Override de ruta base de saves solo para este juego. Null = usar la de la plataforma. */
    suspend fun updateRomSavesPathOverride(romId: Int, path: String?) {
        romDao.updateSavesPathOverride(romId, path?.takeIf { it.isNotBlank() })
    }

    /** Excluye o incluye un juego de la sincronización de partidas. */
    suspend fun updateRomExcludedFromSync(romId: Int, excluded: Boolean) {
        romDao.updateExcludedFromSync(romId, excluded)
    }

    // ── Mappers ────────────────────────────────────────────────────────

    private fun PlatformEntity.toDomain() = Platform(
        id = id,
        slug = slug,
        name = name,
        romCount = romCount,
        visible = visible,
        emulatorId = emulatorId,
        savesPathOverride = savesPathOverride,
        aspectRatio = aspectRatio,
    )

    private fun RomDto.toDomain(): Rom {
        val baseCoverUrl = if (currentServerUrl.isNotEmpty()) "${currentServerUrl.trimEnd('/')}/" else ""
        return Rom(
            id = id,
            name = name.ifBlank { fileName },
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            platformId = platformId,
            platformSlug = platformSlug ?: "",
            coverUrlSmall = resolveCover(pathCoverSmall, urlCover, baseCoverUrl),
            coverUrlLarge = pathCoverLarge?.let { baseCoverUrl + it.removePrefix("/") },
            files = files.map { RomFile(it.filename, it.size) },
            isMulti = multi || hasMultipleFiles,
            revision = revision,
            regions = regions,
            languages = languages,
            genres = genres,
            summary = summary,
            fileNameNoTags = fileNameNoTags,
            fileNameNoExt = fileNameNoExt,
            fileExtension = fileExtension,
            igdbId = igdbId,
            screenshots = mergedScreenshots.map { baseCoverUrl + it.removePrefix("/") },
            videoPath = pathVideo?.let { baseCoverUrl + it.removePrefix("/") },
            manualPath = pathManual?.let { baseCoverUrl + it.removePrefix("/") },
            igdbMetadata = igdbMetadata?.toDomain(),
        )
    }

    /**
     * Prefer url_cover (absolute URL), fall back to path_cover_small (relative path).
     */
    private fun resolveCover(pathCover: String?, urlCover: String?, baseUrl: String): String? {
        return when {
            !urlCover.isNullOrBlank() -> urlCover
            !pathCover.isNullOrBlank() -> baseUrl + pathCover.removePrefix("/")
            else -> null
        }
    }

    private fun es.davidrg.rommsync.data.remote.dto.IgdbMetadataDto.toDomain() =
        es.davidrg.rommsync.domain.model.IgdbMetadata(
            totalRating = totalRating?.toDoubleOrNull(),
            firstReleaseDate = firstReleaseDate,
            genres = genres,
            companies = companies,
            gameModes = gameModes,
            playerCount = playerCount,
        )
}

/**
 * Resultado de un escaneo de biblioteca local.
 *
 * @property detected número de juegos nuevos marcados como descargados.
 * @property scanned número total de ROMs comprobados contra el disco.
 * @property error mensaje de error si el escaneo no pudo completarse.
 */
data class ScanResult(
    val detected: Int = 0,
    val scanned: Int = 0,
    val error: String? = null,
) {
    val isSuccess: Boolean get() = error == null
}
