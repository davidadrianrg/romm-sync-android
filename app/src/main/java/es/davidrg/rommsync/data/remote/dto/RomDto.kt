package es.davidrg.rommsync.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RomFileDto(
    @Json(name = "file_name") val filename: String,
    @Json(name = "file_size_bytes") val size: Long = 0,
    @Json(name = "file_path") val filePath: String? = null,
    @Json(name = "full_path") val fullPath: String? = null,
    @Json(name = "hash") val hash: String? = null,
)

@JsonClass(generateAdapter = true)
data class RomDto(
    @Json(name = "id") val id: Int,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "name") val name: String,
    @Json(name = "fs_name") val fileName: String,
    @Json(name = "fs_name_no_tags") val fileNameNoTags: String? = null,
    @Json(name = "fs_name_no_ext") val fileNameNoExt: String? = null,
    @Json(name = "file_extension") val fileExtension: String? = null,
    @Json(name = "file_path") val filePath: String? = null,
    @Json(name = "fs_size_bytes") val fileSizeBytes: Long = 0,
    @Json(name = "platform_id") val platformId: Int,
    @Json(name = "platform_slug") val platformSlug: String? = null,
    @Json(name = "path_cover_small") val pathCoverSmall: String? = null,
    @Json(name = "path_cover_large") val pathCoverLarge: String? = null,
    @Json(name = "url_cover") val urlCover: String? = null,
    @Json(name = "files") val files: List<RomFileDto> = emptyList(),
    @Json(name = "multi") val isMulti: Boolean = false,
    @Json(name = "has_multiple_files") val hasMultipleFiles: Boolean = false,
    @Json(name = "revision") val revision: String? = null,
    @Json(name = "regions") val regions: List<String> = emptyList(),
    @Json(name = "languages") val languages: List<String> = emptyList(),
)

/**
 * Paginated response for GET /api/roms
 * RomM uses fastapi-pagination which wraps results in {items, total, page, size, pages}
 */
@JsonClass(generateAdapter = true)
data class RomResponse(
    @Json(name = "items") val items: List<RomDto> = emptyList(),
    @Json(name = "total") val total: Int = 0,
    @Json(name = "page") val page: Int? = null,
    @Json(name = "size") val size: Int? = null,
    @Json(name = "pages") val pages: Int? = null,
)
