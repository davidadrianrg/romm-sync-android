package es.davidrg.rommsync.data.update

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Latest release from the GitHub `/releases/latest` endpoint.
 *
 * Only the fields consumed by the in-app updater are modeled.
 */
@JsonClass(generateAdapter = true)
data class GitHubReleaseDto(
    @Json(name = "tag_name") val tagName: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "body") val body: String? = null,
    @Json(name = "html_url") val htmlUrl: String? = null,
    @Json(name = "published_at") val publishedAt: String? = null,
    @Json(name = "assets") val assets: List<GitHubAssetDto> = emptyList(),
)

/**
 * A downloadable file attached to a release (we look for the APK).
 */
@JsonClass(generateAdapter = true)
data class GitHubAssetDto(
    @Json(name = "name") val name: String,
    @Json(name = "browser_download_url") val browserDownloadUrl: String,
    @Json(name = "size") val size: Long = 0,
)
