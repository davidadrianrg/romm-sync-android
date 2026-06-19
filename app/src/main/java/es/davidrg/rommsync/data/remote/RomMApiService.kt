package es.davidrg.rommsync.data.remote

import es.davidrg.rommsync.data.remote.dto.PlatformDto
import es.davidrg.rommsync.data.remote.dto.RomResponse
import es.davidrg.rommsync.data.remote.dto.DeviceRegistrationRequest
import es.davidrg.rommsync.data.remote.dto.DeviceRegistrationResponse
import es.davidrg.rommsync.data.remote.dto.NegotiateRequest
import es.davidrg.rommsync.data.remote.dto.NegotiateResponse
import es.davidrg.rommsync.data.remote.dto.SessionCompleteRequest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
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
     */
    @Streaming
    @GET("api/roms/{id}/content/{file_name}")
    suspend fun downloadRom(
        @Path("id") romId: Int,
        @Path("file_name", encoded = true) fileName: String,
    ): ResponseBody

    // ── Save Sync endpoints ──────────────────────────────────────────────

    /**
     * Register this device with the RomM server for save sync.
     * POST /api/devices
     */
    @POST("api/devices")
    suspend fun registerDevice(
        @Body request: DeviceRegistrationRequest,
    ): DeviceRegistrationResponse

    /**
     * Negotiate sync operations with the server.
     * POST /api/sync/negotiate
     */
    @POST("api/sync/negotiate")
    suspend fun negotiateSync(
        @Body request: NegotiateRequest,
    ): NegotiateResponse

    /**
     * Upload a save file to the server.
     * POST /api/saves
     */
    @Multipart
    @POST("api/saves")
    suspend fun uploadSave(
        @Part file: MultipartBody.Part,
        @Part("rom_id") romId: RequestBody,
    ): ResponseBody

    /**
     * Download a save file from the server.
     * GET /api/saves/{id}/content
     */
    @Streaming
    @GET("api/saves/{id}/content")
    suspend fun downloadSave(
        @Path("id") saveId: Int,
    ): ResponseBody

    /**
     * Complete a sync session.
     * POST /api/sync/sessions/{session_id}/complete
     */
    @POST("api/sync/sessions/{session_id}/complete")
    suspend fun completeSession(
        @Path("session_id") sessionId: String,
        @Body request: SessionCompleteRequest,
    ): ResponseBody
}
