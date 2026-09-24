package com.tbmedtrack.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tbmedtrack.app.data.db.AppDatabase
import com.tbmedtrack.app.data.db.OpSyncStatus
import com.tbmedtrack.app.data.db.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates the local outbox with the configured [SyncClient]. Events are written to
 * Room first (offline-first) and marked PENDING; SyncManager drains PENDING events to the
 * cloud when possible and pulls remote changes. With [NoopSyncClient] this is a no-op that
 * simply marks events synced locally, so behavior is identical whether or not a backend
 * exists.
 */
class SyncManager(private val context: Context) {

    // Swap this for RemoteSyncClient(baseUrl, sessionToken) once a backend is deployed.
    @Volatile var client: SyncClient = NoopSyncClient()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val secureStore by lazy { SecureStore(context) }
    private val meta by lazy { SyncStatusStore(context) }

    /** Always-on sync status for the UI. Recomputed on every sync attempt. */
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** True if the device currently has a validated internet connection. */
    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Recompute [status] from stored metadata + local pending count + connectivity. */
    private suspend fun publishStatus(phase: SyncPhase) {
        val pending = runCatching {
            AppDatabase.get(context).syncOperationDao().countByStatus(OpSyncStatus.PENDING)
        }.getOrDefault(0)
        val configured = !secureStore.baseUrl.isNullOrBlank()
        val resolvedPhase = when {
            !configured -> SyncPhase.LOCAL_ONLY
            phase == SyncPhase.SYNCED && !isOnline() -> SyncPhase.OFFLINE
            else -> phase
        }
        _status.value = SyncStatus(
            phase = resolvedPhase,
            configured = configured,
            lastUploadAt = meta.lastUploadAt,
            lastDownloadAt = meta.lastDownloadAt,
            cloudVersion = meta.cloudVersion,
            pendingCount = pending,
            connectedDevices = meta.connectedDevices
        )
    }

    /** Refresh the status flow without performing a sync (e.g. on screen open). */
    fun refreshStatus() {
        scope.launch {
            // Opportunistically finish auto-connect so the status reflects a real session.
            runCatching { ensureConnected() }
            publishStatus(if (isOnline()) SyncPhase.SYNCED else SyncPhase.OFFLINE)
        }
    }

    /**
     * Choose the active client based on stored config: if a backend URL is present, use the
     * real [RemoteSyncClient]; otherwise stay local-only with [NoopSyncClient]. Call on app
     * start and whenever the backend URL changes.
     */
    fun reconfigure() {
        client = if (!secureStore.baseUrl.isNullOrBlank()) {
            RemoteSyncClient(secureStore)
        } else {
            NoopSyncClient()
        }
    }

    fun secureStore(): SecureStore = secureStore

    /**
     * Redeem a pairing code so THIS device becomes a MONITOR/secondary linked to the main
     * device's account. Requires a configured backend URL. Returns true on success. After
     * redeeming, pulls the current event state so the monitor can display it immediately.
     */
    suspend fun redeemCode(code: String, deviceName: String): Boolean {
        reconfigure()
        if (!client.isConfigured && secureStore.baseUrl.isNullOrBlank()) return false
        val redeemed = client.redeemAuthCode(code, deviceName).isSuccess
        if (redeemed) {
            reconfigure() // now has a session token -> becomes a real RemoteSyncClient
            runCatching { syncNow() }
        }
        return redeemed
    }

    /** Queue a just-recorded event for upload. Safe to call from any thread. */
    fun queue() {
        scope.launch { runCatching { syncNow() } }
    }

    /**
     * Make sure this device is connected to the built-in backend without any user action. Since
     * [SecureStore.baseUrl] always defaults to the app's backend, the only thing missing on a
     * fresh install is a session token — so we bootstrap this device as PRIMARY automatically.
     *
     * A SECONDARY (monitor) device is NOT auto-bootstrapped: it must pair to the main account with
     * a code, so we leave it alone until it redeems one.
     */
    suspend fun ensureConnected() {
        reconfigure()
        if (secureStore.baseUrl.isNullOrBlank()) return
        if (!secureStore.sessionToken.isNullOrBlank()) return // already signed in
        if (!isOnline()) return

        val role = runCatching {
            com.tbmedtrack.app.ServiceLocator.settingsRepository(context).settings.first().deviceRole
        }.getOrDefault("")
        if (role == com.tbmedtrack.app.data.settings.DeviceRoleValue.SECONDARY) return

        val deviceRepo = com.tbmedtrack.app.ServiceLocator.deviceRepository(context)
        runCatching {
            client.bootstrapPrimary(deviceRepo.deviceId, android.os.Build.MODEL ?: "My phone").getOrThrow()
        }
        reconfigure()
    }

    /** Max retry attempts before an operation is parked as FAILED. */
    private val maxRetries = 8

    /** Drain the event outbox + the operation queue, then pull remote changes. */
    suspend fun syncNow() {
        // Auto-connect to the built-in backend first (bootstraps a session token if needed) so
        // the user never has to press "Connect".
        runCatching { ensureConnected() }
        mutex.withLock {
            val db = AppDatabase.get(context)
            val logDao = db.logDao()
            val opDao = db.syncOperationDao()

            val pendingEvents = logDao.getBySyncState(SyncState.PENDING)
            val pendingOps = opDao.byStatus(OpSyncStatus.PENDING)

            if (!client.isConfigured) {
                // Not signed in yet (e.g. offline on first launch, or bootstrap not done). Leave
                // events PENDING so they upload automatically once we have a session token — never
                // mark them synced locally or we'd lose them.
                publishStatus(if (isOnline()) SyncPhase.SYNCING else SyncPhase.OFFLINE)
                return
            }

            if (!isOnline()) {
                // Offline: keep everything pending; it uploads automatically on reconnect.
                publishStatus(SyncPhase.OFFLINE)
                return
            }

            publishStatus(SyncPhase.SYNCING)
            var errored = false

            // Upload events. Idempotent: the server upserts by uuid, so replays are safe.
            if (pendingEvents.isNotEmpty()) {
                runCatching {
                    val uploaded = client.uploadEvents(pendingEvents).getOrThrow()
                    uploaded.forEach { uuid -> logDao.setSyncState(uuid, SyncState.SYNCED) }
                    meta.lastUploadAt = System.currentTimeMillis()
                }.onFailure {
                    errored = true // leave events PENDING for the next attempt (retry on reconnect)
                }
            }

            // Mark operations synced only once their event upload succeeded.
            for (op in pendingOps) {
                val event = logDao.getByUuid(op.recordId)
                val settled = event == null || event.syncState == SyncState.SYNCED
                if (settled) {
                    opDao.setStatus(op.operationId, OpSyncStatus.SYNCED)
                } else {
                    val status = if (op.retryCount + 1 >= maxRetries) OpSyncStatus.FAILED
                    else OpSyncStatus.PENDING
                    opDao.incrementRetry(op.operationId, status)
                }
            }

            // Pull remote changes and merge (TAKEN/REVERTED authoritative).
            var changed = false
            var maxRemote = 0L
            runCatching {
                val remote = client.downloadEvents(0L).getOrThrow()
                for (r in remote) {
                    if (r.updatedAt > maxRemote) maxRemote = r.updatedAt
                    if (mergeRemote(r)) changed = true
                }
                meta.lastDownloadAt = System.currentTimeMillis()
                if (maxRemote > meta.cloudVersion) meta.cloudVersion = maxRemote
            }.onFailure { errored = true }

            // Refresh the connected-device count + cloud version opportunistically.
            runCatching {
                client.syncStatus().getOrNull()?.let { s ->
                    meta.cloudVersion = maxOf(meta.cloudVersion, s.cloudVersion)
                    meta.connectedDevices = s.devices.count { !it.isThisDevice }
                }
            }

            // Refresh widgets so a monitor device reflects the newly-synced status.
            if (changed) {
                com.tbmedtrack.app.widget.MedTrackWidgetProvider.updateAllWidgets(context)
            }
            publishStatus(if (errored) SyncPhase.ERROR else SyncPhase.SYNCED)
        }
    }

    /**
     * Manual CHECK SYNC: verifies the full chain — local pending drained, backend reachable,
     * cloud (Turso) at the latest version, and every authorized monitoring device has actually
     * received that version. Never reports "fully synced" just because Turso has the data; a
     * monitor still behind is surfaced as a warning.
     */
    suspend fun checkSync(): SyncCheckResult {
        val now = System.currentTimeMillis()
        // First push anything pending so the check reflects the true latest state.
        runCatching { syncNow() }

        val configured = !secureStore.baseUrl.isNullOrBlank()
        if (!configured) {
            return SyncCheckResult(
                overallOk = false,
                overallLabel = "Cloud sync not set up",
                lines = listOf(
                    SyncCheckLine("Backend configured", false, "Add your backend URL to enable multi-device sync.")
                ),
                cloudVersion = 0,
                checkedAtMillis = now
            )
        }

        val pending = runCatching {
            AppDatabase.get(context).syncOperationDao().countByStatus(OpSyncStatus.PENDING)
        }.getOrDefault(0)

        val statusResult = client.syncStatus()
        val lines = mutableListOf<SyncCheckLine>()
        var overallOk = true

        // 1) Local upload complete.
        val uploadOk = pending == 0
        lines += SyncCheckLine(
            "Cloud upload complete", uploadOk,
            if (uploadOk) "" else "$pending change(s) still pending upload."
        )
        if (!uploadOk) overallOk = false

        val cloud = statusResult.getOrNull()
        if (cloud == null) {
            lines += SyncCheckLine("Turso database updated", false, "Could not reach the sync server.")
            return SyncCheckResult(false, "🔴 Sync problem", lines, meta.cloudVersion, now)
        }
        meta.cloudVersion = maxOf(meta.cloudVersion, cloud.cloudVersion)

        // 2) Turso reachable + has the latest version.
        lines += SyncCheckLine("Turso database updated", true, "Cloud version #${cloud.cloudVersion}")

        // 3) Per-device received status (primary + monitors).
        cloud.devices.forEach { d ->
            val roleLabel = if (d.role.equals("PRIMARY", true)) "Primary device" else "${d.name} (monitor)"
            val ok = d.upToDate
            if (!ok) overallOk = false
            lines += SyncCheckLine(
                (if (d.isThisDevice) "This device" else roleLabel) + " synced",
                ok,
                if (ok) "#${d.lastSyncedVersion}" else "Last received #${d.lastSyncedVersion} • waiting for #${cloud.cloudVersion}"
            )
        }

        meta.connectedDevices = cloud.devices.count { !it.isThisDevice }
        publishStatus(if (overallOk) SyncPhase.SYNCED else SyncPhase.SYNCING)

        val label = when {
            overallOk -> "🟢 Everything is synchronized"
            cloud.devices.any { !it.upToDate && !it.role.equals("PRIMARY", true) } -> "🟡 Waiting for monitoring device"
            else -> "🟡 Sync pending"
        }
        return SyncCheckResult(overallOk, label, lines, cloud.cloudVersion, now)
    }

    /** Requeue FAILED operations for another attempt (e.g. when connectivity returns). */
    suspend fun retryFailed() {
        val opDao = AppDatabase.get(context).syncOperationDao()
        opDao.byStatus(com.tbmedtrack.app.data.db.OpSyncStatus.FAILED).forEach {
            opDao.setStatus(it.operationId, com.tbmedtrack.app.data.db.OpSyncStatus.PENDING)
        }
        syncNow()
    }

    /**
     * Merge a remote event with the local copy using append-oriented, TAKEN-authoritative
     * rules: never downgrade a locally-recorded "taken" event.
     */
    private suspend fun mergeRemote(remote: com.tbmedtrack.app.data.db.MedicationLog): Boolean {
        val db = AppDatabase.get(context)
        val local = db.logDao().getByUuid(remote.uuid)
        if (local == null) {
            db.logDao().insert(remote.copy(id = 0, syncState = SyncState.SYNCED))
            return true
        }
        // Newer updatedAt wins (a revert has a newer timestamp than the original take, so a
        // stale remote TAKEN can never overwrite a fresh local revert). On an exact tie, a
        // TAKEN is preferred so a genuine take isn't lost to a same-instant scheduled row.
        val keepRemote = when {
            remote.updatedAt > local.updatedAt -> true
            remote.updatedAt < local.updatedAt -> false
            else -> remote.status == com.tbmedtrack.app.data.db.DoseStatus.TAKEN &&
                local.status != com.tbmedtrack.app.data.db.DoseStatus.TAKEN
        }
        if (keepRemote) {
            db.logDao().update(remote.copy(id = local.id, syncState = SyncState.SYNCED))
            return true
        }
        return false
    }
}
