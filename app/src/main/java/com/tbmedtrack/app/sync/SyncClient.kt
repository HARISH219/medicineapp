package com.tbmedtrack.app.sync

import com.tbmedtrack.app.data.db.MedicationLog

/**
 * Abstraction over the cloud sync backend. The app talks only to this interface, never
 * directly to Turso, so the Turso auth token never ships in the APK. A real
 * implementation ([RemoteSyncClient]) forwards to your deployed backend over HTTPS; the
 * default [NoopSyncClient] keeps everything local so the app is fully functional offline
 * and compiles/runs with no backend configured.
 *
 * See docs/BACKEND.md for the backend contract and Turso + FCM wiring.
 */
interface SyncClient {

    /** Whether a backend is configured/reachable. */
    val isConfigured: Boolean

    /** Push a batch of locally-recorded events to the cloud. Returns uuids accepted. */
    suspend fun uploadEvents(events: List<MedicationLog>): Result<List<String>>

    /** Pull events updated since [sinceMillis] from the cloud. */
    suspend fun downloadEvents(sinceMillis: Long): Result<List<MedicationLog>>

    /** Establish this device as PRIMARY and obtain a session token (first connect). */
    suspend fun bootstrapPrimary(deviceId: String, deviceName: String): Result<Unit>

    /** Generate a short-lived device authorization code (primary device). */
    suspend fun createAuthCode(): Result<AuthCode>

    /** Redeem an authorization code on a new device; returns the account config. */
    suspend fun redeemAuthCode(code: String, deviceName: String): Result<Unit>

    /** Revoke a device's access (primary device). */
    suspend fun revokeDevice(deviceId: String): Result<Unit>

    /** Cloud sync-version status: the cloud's latest version + each device's acked version. */
    suspend fun syncStatus(): Result<CloudSyncStatus>
}

data class AuthCode(val code: String, val expiresAtMillis: Long)

/** One device's synchronization state as reported by the backend. */
data class RemoteDeviceStatus(
    val deviceId: String,
    val name: String,
    val role: String,
    val lastSyncedVersion: Long,
    val upToDate: Boolean,
    val online: Boolean,
    val lastActiveAt: Long,
    val isThisDevice: Boolean
)

/** Account-wide sync-version snapshot from the backend. */
data class CloudSyncStatus(
    val cloudVersion: Long,
    val devices: List<RemoteDeviceStatus>
)

/**
 * Local-only no-op implementation. Everything the user does works and is stored locally;
 * sync operations succeed as no-ops. Swap in [RemoteSyncClient] once a backend is deployed.
 */
class NoopSyncClient : SyncClient {
    override val isConfigured: Boolean = false
    override suspend fun uploadEvents(events: List<MedicationLog>) = Result.success(events.map { it.uuid })
    override suspend fun downloadEvents(sinceMillis: Long) = Result.success(emptyList<MedicationLog>())
    override suspend fun bootstrapPrimary(deviceId: String, deviceName: String): Result<Unit> =
        Result.failure(IllegalStateException("No sync backend configured"))
    override suspend fun createAuthCode(): Result<AuthCode> =
        Result.failure(IllegalStateException("No sync backend configured"))
    override suspend fun redeemAuthCode(code: String, deviceName: String): Result<Unit> =
        Result.failure(IllegalStateException("No sync backend configured"))
    override suspend fun revokeDevice(deviceId: String): Result<Unit> = Result.success(Unit)
    override suspend fun syncStatus(): Result<CloudSyncStatus> =
        Result.failure(IllegalStateException("No sync backend configured"))
}
