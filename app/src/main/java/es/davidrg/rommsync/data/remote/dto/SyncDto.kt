package es.davidrg.rommsync.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Device Registration ────────────────────────────────────────────────
// POST /api/devices -> DeviceCreateResponse

@JsonClass(generateAdapter = true)
data class DeviceRegistrationRequest(
    @Json(name = "name") val name: String? = null,
    @Json(name = "platform") val platform: String? = "android",
    @Json(name = "client") val client: String? = "romm-sync-android",
    @Json(name = "client_version") val clientVersion: String? = null,
    @Json(name = "hostname") val hostname: String? = null,
    @Json(name = "mac_address") val macAddress: String? = null,
    @Json(name = "sync_mode") val syncMode: String = "push_pull",
    @Json(name = "allow_existing") val allowExisting: Boolean = true,
)

@JsonClass(generateAdapter = true)
data class DeviceRegistrationResponse(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
)

// ── Sync Negotiation ───────────────────────────────────────────────────
// POST /api/sync/negotiate -> SyncNegotiateResponse

@JsonClass(generateAdapter = true)
data class NegotiateRequest(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "saves") val saves: List<ClientSaveState>,
)

@JsonClass(generateAdapter = true)
data class ClientSaveState(
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "slot") val slot: String? = null,
    @Json(name = "emulator") val emulator: String? = null,
    @Json(name = "content_hash") val contentHash: String? = null,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "file_size_bytes") val fileSizeBytes: Int,
)

@JsonClass(generateAdapter = true)
data class NegotiateResponse(
    @Json(name = "session_id") val sessionId: Int,
    @Json(name = "operations") val operations: List<SyncOperation>,
    @Json(name = "total_upload") val totalUpload: Int = 0,
    @Json(name = "total_download") val totalDownload: Int = 0,
    @Json(name = "total_conflict") val totalConflict: Int = 0,
    @Json(name = "total_no_op") val totalNoOp: Int = 0,
)

@JsonClass(generateAdapter = true)
data class SyncOperation(
    @Json(name = "action") val action: String, // upload, download, conflict, no_op
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "save_id") val saveId: Int? = null,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "slot") val slot: String? = null,
    @Json(name = "emulator") val emulator: String? = null,
    @Json(name = "reason") val reason: String? = null,
    @Json(name = "server_updated_at") val serverUpdatedAt: String? = null,
    @Json(name = "server_content_hash") val serverContentHash: String? = null,
)

// ── Session Completion ─────────────────────────────────────────────────
// POST /api/sync/sessions/{session_id}/complete

@JsonClass(generateAdapter = true)
data class SessionCompleteRequest(
    @Json(name = "operations_completed") val operationsCompleted: Int = 0,
    @Json(name = "operations_failed") val operationsFailed: Int = 0,
    @Json(name = "play_sessions") val playSessions: List<PlaySessionDto>? = null,
)

@JsonClass(generateAdapter = true)
data class PlaySessionDto(
    @Json(name = "rom_id") val romId: Int? = null,
    @Json(name = "save_slot") val saveSlot: String? = null,
    @Json(name = "start_time") val startTime: String,
    @Json(name = "end_time") val endTime: String,
    @Json(name = "duration_ms") val durationMs: Long,
)
