package es.davidrg.rommsync.domain.model

/**
 * Represents a ROM (game) from the RomM server.
 */
data class Rom(
    val id: Int,
    val name: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val platformId: Int,
    val platformSlug: String,
    val coverUrlSmall: String?,
    val coverUrlLarge: String?,
    val files: List<RomFile>,
    val isMulti: Boolean,
    val revision: String?,
    val regions: List<String>,
    val languages: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val summary: String? = null,
    val fileNameNoTags: String? = null,
    val fileNameNoExt: String? = null,
    val fileExtension: String? = null,
    val igdbId: Int? = null,
    // ── Media fields for ES-DE export ──────────────────────────────
    val screenshots: List<String> = emptyList(),
    val videoPath: String? = null,
    val manualPath: String? = null,
    val igdbMetadata: IgdbMetadata? = null,
)

/**
 * Subset of IGDB metadata used for gamelist.xml generation.
 */
data class IgdbMetadata(
    val totalRating: Double? = null,
    val firstReleaseDate: Long? = null,
    val genres: List<String> = emptyList(),
    val companies: List<String> = emptyList(),
    val gameModes: List<String> = emptyList(),
    val playerCount: String? = null,
)

data class RomFile(
    val filename: String,
    val size: Long,
)

/**
 * Local download status of a ROM.
 */
enum class DownloadStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
}

/**
 * UI-friendly ROM with local download status merged.
 */
data class RomWithStatus(
    val rom: Rom,
    val status: DownloadStatus,
    val progress: Int = 0, // 0-100
)
