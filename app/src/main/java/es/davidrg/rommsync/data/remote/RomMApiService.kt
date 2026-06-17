package es.davidrg.rommsync.data.remote

import es.davidrg.rommsync.data.remote.dto.PlatformDto
import es.davidrg.rommsync.data.remote.dto.RomResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.QueryMap
import retrofit2.http.Streaming

interface RomMApiService {

    /**
     * Get all platforms available on the RomM server.
     * GET /api/platforms
     *
     * RomM returns a bare JSON array, NOT a wrapped object.
     */
    @GET("api/platforms")
    suspend fun getPlatforms(): List<PlatformDto>

    /**
     * Get ROMs filtered by platform with pagination.
     * GET /api/roms?platform_ids=[id]&limit=50&offset=0
     *
     * RomM uses fastapi-pagination → response is {items, total, page, size, pages}.
     * IMPORTANT: Use platform_ids (plural), NOT platform_id (singular).
     */
    @GET("api/roms")
    suspend fun getRoms(
        @QueryMap params: Map<String, String>,
    ): RomResponse

    /**
     * Download ROM content — returns a streaming response body.
     * GET /api/roms/{id}/content/{file_name}
     *
     * For multi-file ROMs, the server may use mod_zip (Content-Length = -1)
     * and return a dynamically zipped stream.
     *
     * When [range] is non-null, it is sent as the HTTP `Range` header so the
     * server can return `206 Partial Content` and only the missing bytes are
     * transferred. If the server does not support range requests (or the
     * resource changed) it returns `200 OK` with the full body, in which case
     * the caller must restart from scratch.
     *
     * Returns a [Response] wrapper so callers can inspect the status code
     * (200 vs 206 vs 416) to decide between fresh download, resume or
     * "already complete".
     */
    @Streaming
    @GET("api/roms/{id}/content/{file_name}")
    suspend fun downloadRom(
        @Path("id") romId: Int,
        @Path("file_name", encoded = true) fileName: String,
        @Header("Range") range: String? = null,
    ): Response<ResponseBody>
}
