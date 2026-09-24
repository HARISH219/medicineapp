package com.tbmedtrack.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A queued cloud-sync operation. Every cloud-relevant change enqueues one row; the sync
 * engine drains them and marks them SYNCED. Operations are idempotent: they carry the
 * record's stable UUID so replays (retry, restart, background sync) never duplicate data.
 */
@Entity(
    tableName = "sync_operations",
    indices = [Index("syncStatus"), Index(value = ["operationId"], unique = true)]
)
data class SyncOperation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operationId: String = java.util.UUID.randomUUID().toString(),
    val deviceId: String = "",
    /** UUID of the affected record (e.g. medication_events.uuid) */
    val recordId: String,
    /** MEDICATION_TAKEN, MEDICATION_REVERTED, MEDICINE_UPDATED, SCHEDULE_UPDATED, ... */
    val operationType: String,
    /** optional JSON payload for the operation */
    val payload: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val syncStatus: String = OpSyncStatus.PENDING,
    val retryCount: Int = 0
)

object OpSyncStatus {
    const val PENDING = "PENDING"
    const val SYNCED = "SYNCED"
    const val FAILED = "FAILED"
}

object OperationType {
    const val MEDICATION_TAKEN = "MEDICATION_TAKEN"
    const val MEDICATION_TAKEN_LATE = "MEDICATION_TAKEN_LATE"
    const val MEDICATION_REVERTED = "MEDICATION_REVERTED"
    const val MEDICATION_SNOOZED = "MEDICATION_SNOOZED"
    const val MEDICATION_HISTORICAL = "MEDICATION_HISTORICAL"
    const val MEDICINE_UPDATED = "MEDICINE_UPDATED"
    const val SCHEDULE_UPDATED = "SCHEDULE_UPDATED"
}
