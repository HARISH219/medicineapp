package com.tbmedtrack.app.sync

import android.content.Context

/** Coarse phase for the always-on sync UI. */
enum class SyncPhase {
    /** everything uploaded/downloaded, nothing pending */
    SYNCED,
    /** an upload/download is in progress */
    SYNCING,
    /** no internet — changes are stored locally and will upload on reconnect */
    OFFLINE,
    /** a sync attempt failed */
    ERROR,
    /** no backend configured yet (local-only) */
    LOCAL_ONLY
}

/** Live, always-on cloud-sync status surfaced in Settings. */
data class SyncStatus(
    val phase: SyncPhase = SyncPhase.LOCAL_ONLY,
    val configured: Boolean = false,
    val lastUploadAt: Long = 0L,
    val lastDownloadAt: Long = 0L,
    /** cloud's latest version (max event updatedAt) as last observed */
    val cloudVersion: Long = 0L,
    /** number of locally-recorded events not yet confirmed uploaded */
    val pendingCount: Int = 0,
    /** connected authorized devices (from last sync-status check) */
    val connectedDevices: Int = 0
)

/** One line in the CHECK SYNC result. */
data class SyncCheckLine(val label: String, val ok: Boolean, val detail: String = "")

/** Full result of a manual CHECK SYNC. */
data class SyncCheckResult(
    val overallOk: Boolean,
    val overallLabel: String,
    val lines: List<SyncCheckLine>,
    val cloudVersion: Long,
    val checkedAtMillis: Long
)

/**
 * Tiny persisted store for sync metadata (survives restarts). Kept separate from the encrypted
 * SecureStore because none of this is sensitive.
 */
class SyncStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences("tbmedtrack_sync_meta", Context.MODE_PRIVATE)

    var lastUploadAt: Long
        get() = prefs.getLong("last_upload_at", 0L)
        set(v) { prefs.edit().putLong("last_upload_at", v).apply() }

    var lastDownloadAt: Long
        get() = prefs.getLong("last_download_at", 0L)
        set(v) { prefs.edit().putLong("last_download_at", v).apply() }

    var cloudVersion: Long
        get() = prefs.getLong("cloud_version", 0L)
        set(v) { prefs.edit().putLong("cloud_version", v).apply() }

    var connectedDevices: Int
        get() = prefs.getInt("connected_devices", 0)
        set(v) { prefs.edit().putInt("connected_devices", v).apply() }
}
