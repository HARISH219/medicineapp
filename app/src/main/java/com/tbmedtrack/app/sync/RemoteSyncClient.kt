package com.tbmedtrack.app.sync

import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.data.db.MedicationLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real HTTPS sync client. Talks to the backend described in docs/BACKEND.md (the backend holds
 * the Turso token; this app never does). Reads its base URL + per-device session token from
 * [SecureStore]. Uploads are idempotent (server upserts by uuid).
 *
 * Not wired by default — [SyncManager] uses [NoopSyncClient] until you deploy the backend and
 * call `SyncManager.client = RemoteSyncClient(secureStore)`.
 */
class RemoteSyncClient(private val store: SecureStore) : SyncClient {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val isConfigured: Boolean get() = store.isConfigured

    @Serializable
    private data class EventDto(
        val uuid: String,
        val deviceId: String,
        val medicineName: String,
        val doseText: String,
        val scheduledDateTime: Long,
        val scheduledEpochDay: Long,
        val actualTakenDateTime: Long?,
        val status: String,
        val historical: Boolean,
        val snoozeCount: Int,
        val createdAt: Long,
        val updatedAt: Long
    )

    @Serializable private data class UploadRequest(val events: List<EventDto>)
    @Serializable private data class UploadResponse(val accepted: List<String> = emptyList())
    @Serializable private data class DownloadResponse(val events: List<EventDto> = emptyList())
    @Serializable private data class BootstrapRequest(val deviceId: String, val deviceName: String)
    @Serializable private data class BootstrapResponse(val sessionToken: String, val userId: String, val role: String)
    @Serializable private data class AuthCodeResponse(val code: String, val expiresAt: Long)
    @Serializable private data class RedeemRequest(val code: String, val deviceName: String)
    @Serializable private data class RedeemResponse(val sessionToken: String)
    @Serializable private data class RevokeRequest(val deviceId: String)

    @Serializable
    private data class DeviceStatusDto(
        val deviceId: String = "",
        val name: String = "Device",
        val role: String = "",
        val lastSyncedVersion: Long = 0,
        val upToDate: Boolean = false,
        val online: Boolean = false,
        val lastActiveAt: Long = 0,
        val isThisDevice: Boolean = false
    )
    @Serializable
    private data class SyncStatusResponse(
        val cloudVersion: Long = 0,
        val devices: List<DeviceStatusDto> = emptyList()
    )

    override suspend fun uploadEvents(events: List<MedicationLog>): Result<List<String>> =
        withContext(Dispatchers.IO) {
            if (events.isEmpty()) return@withContext Result.success(emptyList())
            runCatching {
                val body = json.encodeToString(UploadRequest(events.map { it.toDto() }))
                val resp = post("/v1/events", body)
                json.decodeFromString<UploadResponse>(resp).accepted
            }
        }

    override suspend fun downloadEvents(sinceMillis: Long): Result<List<MedicationLog>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = get("/v1/events?since=$sinceMillis")
                json.decodeFromString<DownloadResponse>(resp).events.map { it.toLog() }
            }
        }

    override suspend fun bootstrapPrimary(deviceId: String, deviceName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = post("/v1/bootstrap", json.encodeToString(BootstrapRequest(deviceId, deviceName)))
                val r = json.decodeFromString<BootstrapResponse>(resp)
                store.sessionToken = r.sessionToken
            }
        }

    override suspend fun createAuthCode(): Result<AuthCode> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = post("/v1/devices/auth-code", "{}")
            val r = json.decodeFromString<AuthCodeResponse>(resp)
            AuthCode(r.code, r.expiresAt)
        }
    }

    override suspend fun redeemAuthCode(code: String, deviceName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = post("/v1/devices/redeem", json.encodeToString(RedeemRequest(code, deviceName)))
                val r = json.decodeFromString<RedeemResponse>(resp)
                store.sessionToken = r.sessionToken
            }
        }

    override suspend fun revokeDevice(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { post("/v1/devices/revoke", json.encodeToString(RevokeRequest(deviceId))); Unit }
    }

    override suspend fun syncStatus(): Result<CloudSyncStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = get("/v1/sync-status")
            val r = json.decodeFromString<SyncStatusResponse>(resp)
            CloudSyncStatus(
                cloudVersion = r.cloudVersion,
                devices = r.devices.map {
                    RemoteDeviceStatus(
                        deviceId = it.deviceId,
                        name = it.name,
                        role = it.role,
                        lastSyncedVersion = it.lastSyncedVersion,
                        upToDate = it.upToDate,
                        online = it.online,
                        lastActiveAt = it.lastActiveAt,
                        isThisDevice = it.isThisDevice
                    )
                }
            )
        }
    }

    // --- HTTP helpers ---

    private fun get(path: String): String = request("GET", path, null)
    private fun post(path: String, body: String): String = request("POST", path, body)

    private fun request(method: String, path: String, body: String?): String {
        val base = store.baseUrl?.trimEnd('/') ?: error("No backend URL configured")
        val url = URL("$base$path")
        require(url.protocol == "https") { "Backend URL must use HTTPS" }
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/json")
        store.sessionToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) error("HTTP $code: $text")
        return text
    }

    private fun MedicationLog.toDto() = EventDto(
        uuid, deviceId, medicineName, doseText, scheduledDateTime, scheduledEpochDay,
        actualTakenDateTime, status.name, historical, snoozeCount, createdAt, updatedAt
    )

    private fun EventDto.toLog() = MedicationLog(
        uuid = uuid,
        deviceId = deviceId,
        medicineId = 0,
        scheduleId = 0,
        scheduledDateTime = scheduledDateTime,
        scheduledEpochDay = scheduledEpochDay,
        actualTakenDateTime = actualTakenDateTime,
        status = runCatching { DoseStatus.valueOf(status) }.getOrDefault(DoseStatus.SCHEDULED),
        snoozeCount = snoozeCount,
        medicineName = medicineName,
        doseText = doseText,
        historical = historical,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
