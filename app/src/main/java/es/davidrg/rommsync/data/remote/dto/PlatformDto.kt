package es.davidrg.rommsync.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PlatformDto(
    val id: Int,
    val slug: String,
    @Json(name = "fs_slug") val fsSlug: String?,
    val name: String,
    @Json(name = "rom_count") val romCount: Int = 0,
    @Json(name = "igdb_id") val igdbId: Int? = null,
)

@JsonClass(generateAdapter = true)
data class PlatformResponse(
    val total: Int = 0,
    val items: List<PlatformDto> = emptyList(),
)
