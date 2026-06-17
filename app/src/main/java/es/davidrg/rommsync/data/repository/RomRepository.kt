package es.davidrg.rommsync.data.repository

import es.davidrg.rommsync.data.local.dao.PlatformDao
import es.davidrg.rommsync.data.local.dao.RomDao
import es.davidrg.rommsync.data.local.entity.DownloadedRomEntity
import es.davidrg.rommsync.data.local.entity.PlatformEntity
import es.davidrg.rommsync.data.remote.NetworkModule
import es.davidrg.rommsync.data.remote.RomMApiService
import es.davidrg.rommsync.data.remote.dto.PlatformDto
import es.davidrg.rommsync.data.remote.dto.RomDto
import es.davidrg.rommsync.domain.model.Platform
import es.davidrg.rommsync.domain.model.Rom
import es.davidrg.rommsync.domain.model.RomFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Central repository for all RomM data operations.
 * Bridges the remote API (Retrofit), local cache (Room), and domain models.
 */
class RomRepository(
    private val platformDao: PlatformDao,
    private val romDao: RomDao,
) {

    // ── Remote API (dynamically created from user config) ──────────────

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

    private fun api(): RomMApiService =
        apiService ?: throw IllegalStateException("API not configured. Call configureApi() first.")

    // ── Platforms ──────────────────────────────────────────────────────

    /** Fetch platforms from server, cache to Room, return domain models. */
    suspend fun fetchAndCachePlatforms(): List<Platform> {
        val response = api().getPlatforms()
        val entities = response.items.map { it.toEntity() }
        platformDao.upsertAll(entities)
        return entities.map { it.toDomain() }
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

    // ── ROMs ───────────────────────────────────────────────────────────

    /** Fetch paginated ROMs for a platform from the server. */
    suspend fun fetchRoms(platformId: Int, limit: Int = 50, offset: Int = 0): List<Rom> {
        val response = api().getRoms(
            platformIds = listOf(platformId),
            limit = limit,
            offset = offset,
        )
        return response.items.map { it.toDomain() }
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

    suspend fun isRomDownloaded(romId: Int): Boolean = romDao.isDownloaded(romId)

    // ── Mappers ────────────────────────────────────────────────────────

    private fun PlatformDto.toEntity() = PlatformEntity(
        id = id,
        slug = slug,
        name = name,
        romCount = romCount,
        visible = true,
    )

    private fun PlatformEntity.toDomain() = Platform(
        id = id,
        slug = slug,
        name = name,
        romCount = romCount,
        visible = visible,
    )

    private fun RomDto.toDomain(): Rom {
        val baseCoverUrl = if (currentServerUrl.isNotEmpty()) "$currentServerUrl/" else ""
        return Rom(
            id = id,
            name = name.ifBlank { fileName },
            fileName = fileName,
            fileSizeBytes = fileSizeBytes,
            platformId = platformId,
            platformSlug = platformSlug ?: "",
            coverUrlSmall = pathCoverSmall?.let { baseCoverUrl + it.removePrefix("/") },
            coverUrlLarge = pathCoverLarge?.let { baseCoverUrl + it.removePrefix("/") },
            files = files.map { RomFile(it.filename, it.size) },
            isMulti = isMulti,
            revision = revision,
            regions = regions,
        )
    }
}
