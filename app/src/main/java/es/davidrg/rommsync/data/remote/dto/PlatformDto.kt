package es.davidrg.rommsync.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PlatformDto(
    @Json(name = "id") val id: Int,
    @Json(name = "slug") val slug: String,
    @Json(name = "fs_slug") val fsSlug: String? = null,
    @Json(name = "name") val name: String,
    @Json(name = "rom_count") val romCount: Int = 0,
    @Json(name = "display_name") val displayName: String? = null,
    @Json(name = "igdb_id") val igdbId: Int? = null,
    @Json(name = "url_logo") val logoUrl: String? = null,
    @Json(name = "aspect_ratio") val aspectRatio: String? = null,
)
