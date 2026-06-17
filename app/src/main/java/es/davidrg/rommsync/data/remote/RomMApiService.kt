package es.davidrg.rommsync.data.remote

import es.davidrg.rommsync.data.remote.dto.PlatformResponse
import es.davidrg.rommsync.data.remote.dto.RomResponse
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface RomMApiService {

    /**
     * Get all platforms available on the RomM server.
     * GET /api/platforms
     */
    @GET("/api/platforms")
    suspend fun getPlatforms(): PlatformResponse

    /**
     * Get ROMs filtered by platform with pagination.
     * GET /api/roms?platform_ids=[id]&limit=50&offset=0
     *
     * IMPORTANT: Use platform_ids (plural array), NOT platform_id (singular).
     * The singular form does not filter correctly in several server versions.
     * Pagination via limit+offset is MANDATORY to avoid server OOM on large libraries.
     */
    @GET("/api/roms")
    suspend fun getRoms(
        @Query("platform_ids") platformIds: List<Int>,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0,
    ): RomResponse

    /**
     * Download ROM content — returns a streaming response body.
     * GET /api/roms/{id}/content/{file_name}
     *
     * For multi-file ROMs, the server may use mod_zip (Content-Length = -1)
     * and return a dynamically zipped stream.
     */
    @GET("/api/roms/{id}/content/{file_name}")
    @retrofit2.http.Streaming
    suspend fun downloadRom(
        @Path("id") romId: Int,
        @Path("file_name") fileName: String,
    ): ResponseBody
}
