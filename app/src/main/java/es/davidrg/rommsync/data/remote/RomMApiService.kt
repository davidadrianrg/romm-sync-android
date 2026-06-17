package es.davidrg.rommsync.data.remote

import es.davidrg.rommsync.data.remote.dto.PlatformDto
import es.davidrg.rommsync.data.remote.dto.RomResponse
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

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
     */
    @Streaming
    @GET("api/roms/{id}/content/{file_name}")
    suspend fun downloadRom(
        @Path("id") romId: Int,
        @Path("file_name", encoded = true) fileName: String,
    ): ResponseBody
}
