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

    // ── Mappers ────────────────────────────────────────────────────────

    private fun PlatformEntity.toDomain() = Platform(
        id = id,
        slug = slug,
        name = name,
        romCount = romCount,
        visible = visible,
        emulatorId = emulatorId,
        savesPathOverride = savesPathOverride,
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
            isMulti = isMulti || hasMultipleFiles,
            revision = revision,
            regions = regions,
            languages = languages,
            genres = genres,
            summary = summary,
            fileNameNoTags = fileNameNoTags,
            fileExtension = fileExtension,
            igdbId = igdbId,
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
}
