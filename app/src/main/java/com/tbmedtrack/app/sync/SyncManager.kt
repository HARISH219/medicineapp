package com.tbmedtrack.app.sync

import android.content.Context
import com.tbmedtrack.app.data.db.AppDatabase
import com.tbmedtrack.app.data.db.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    /** Queue a just-recorded event for upload. Safe to call from any thread. */
    fun queue() {
        scope.launch { runCatching { syncNow() } }
    }

    /** Max retry attempts before an operation is parked as FAILED. */
    private val maxRetries = 8

    /** Drain the event outbox + the operation queue, then pull remote changes. */
    suspend fun syncNow() {
        mutex.withLock {
            val db = AppDatabase.get(context)
            val logDao = db.logDao()
            val opDao = db.syncOperationDao()

            val pendingEvents = logDao.getBySyncState(SyncState.PENDING)
            val pendingOps = opDao.byStatus(com.tbmedtrack.app.data.db.OpSyncStatus.PENDING)

            if (!client.isConfigured) {
                // No backend: settle local queues so they don't grow unbounded. Data stays local.
                pendingEvents.forEach { logDao.setSyncState(it.uuid, SyncState.SYNCED) }
                pendingOps.forEach { opDao.setStatus(it.operationId, com.tbmedtrack.app.data.db.OpSyncStatus.SYNCED) }
                return
            }

            // Upload events. Idempotent: the server upserts by uuid, so replays are safe.
            runCatching {
                val uploaded = client.uploadEvents(pendingEvents).getOrThrow()
                uploaded.forEach { uuid -> logDao.setSyncState(uuid, SyncState.SYNCED) }
            }.onFailure {
                // leave events PENDING for the next attempt (retry on reconnect)
            }

            // Mark operations synced only once their event upload succeeded.
            for (op in pendingOps) {
                val event = logDao.getByUuid(op.recordId)
                val settled = event == null || event.syncState == SyncState.SYNCED
                if (settled) {
                    opDao.setStatus(op.operationId, com.tbmedtrack.app.data.db.OpSyncStatus.SYNCED)
                } else {
                    val status = if (op.retryCount + 1 >= maxRetries)
                        com.tbmedtrack.app.data.db.OpSyncStatus.FAILED
                    else com.tbmedtrack.app.data.db.OpSyncStatus.PENDING
                    opDao.incrementRetry(op.operationId, status)
                }
            }

            // Pull remote changes and merge (TAKEN/REVERTED authoritative).
            runCatching {
                val remote = client.downloadEvents(0L).getOrThrow()
                for (r in remote) mergeRemote(r)
            }
        }
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
    private suspend fun mergeRemote(remote: com.tbmedtrack.app.data.db.MedicationLog) {
        val db = AppDatabase.get(context)
        val local = db.logDao().getByUuid(remote.uuid)
        if (local == null) {
            db.logDao().insert(remote.copy(id = 0, syncState = SyncState.SYNCED))
            return
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
        }
    }
}
