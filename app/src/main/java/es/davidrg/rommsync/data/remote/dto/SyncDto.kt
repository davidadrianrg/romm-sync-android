package es.davidrg.rommsync.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Device Registration ────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class DeviceRegistrationRequest(
    @Json(name = "name") val name: String,
    @Json(name = "platform") val platform: String = "android",
    @Json(name = "hostname") val hostname: String? = null,
    @Json(name = "mac") val mac: String? = null,
    @Json(name = "sync_mode") val syncMode: String = "push_pull",
    @Json(name = "paths") val paths: DevicePaths,
)

@JsonClass(generateAdapter = true)
data class DevicePaths(
    @Json(name = "roms") val roms: String,
    @Json(name = "saves") val saves: String,
    @Json(name = "states") val states: String,
)

@JsonClass(generateAdapter = true)
data class DeviceRegistrationResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "platform") val platform: String? = null,
)

// ── Sync Negotiation ───────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class NegotiateRequest(
    @Json(name = "device_id") val deviceId: Int,
    @Json(name = "roms") val roms: List<RomSaveInfo>,
)

@JsonClass(generateAdapter = true)
data class RomSaveInfo(
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "saves") val saves: List<SaveFileInfo>,
)

@JsonClass(generateAdapter = true)
data class SaveFileInfo(
    @Json(name = "file") val file: String,
    @Json(name = "mtime") val mtime: String,
    @Json(name = "sha1") val sha1: String,
)

@JsonClass(generateAdapter = true)
data class NegotiateResponse(
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "operations") val operations: List<SyncOperation>,
)

@JsonClass(generateAdapter = true)
data class SyncOperation(
    @Json(name = "type") val type: String, // upload, download, conflict, noop
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "file") val file: String,
    @Json(name = "destination") val destination: String? = null,
    @Json(name = "source") val source: String? = null,
    @Json(name = "dest_path") val destPath: String? = null,
    @Json(name = "resolution") val resolution: String? = null,
)

// ── Session Completion ─────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class SessionCompleteRequest(
    @Json(name = "operations_completed") val operationsCompleted: Int,
    @Json(name = "operations_failed") val operationsFailed: Int,
    @Json(name = "play_sessions") val playSessions: List<PlaySessionDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class PlaySessionDto(
    @Json(name = "rom_id") val romId: Int,
    @Json(name = "start") val start: String,
    @Json(name = "end") val end: String,
    @Json(name = "duration_seconds") val durationSeconds: Long,
)
