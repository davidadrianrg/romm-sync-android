package es.davidrg.rommsync.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RomFileDto(
    val filename: String,
    val size: Long = 0,
    val hash: String? = null,
)

@JsonClass(generateAdapter = true)
data class RomDto(
    val id: Int,
    val slug: String,
    val name: String,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_name_no_tags") val fileNameNoTags: String? = null,
    @Json(name = "file_extension") val fileExtension: String? = null,
    @Json(name = "file_path") val filePath: String? = null,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long = 0,
    @Json(name = "platform_id") val platformId: Int,
    @Json(name = "platform_slug") val platformSlug: String? = null,
    @Json(name = "path_cover_s") val pathCoverSmall: String? = null,
    @Json(name = "path_cover_l") val pathCoverLarge: String? = null,
    val files: List<RomFileDto> = emptyList(),
    @Json(name = "is_multi") val isMulti: Boolean = false,
    val revision: String? = null,
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class RomResponse(
    val total: Int = 0,
    val items: List<RomDto> = emptyList(),
)
