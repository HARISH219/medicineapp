package com.tbmedtrack.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only audit trail of medication-event status changes. Events themselves are never
 * deleted; every transition (e.g. TAKEN -> REVERTED) is recorded here so the true history
 * is preserved and can be synchronized.
 */
@Entity(
    tableName = "medication_event_audit",
    indices = [Index("eventUuid"), Index("timestamp")]
)
data class MedicationEventAudit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = java.util.UUID.randomUUID().toString(),
    /** uuid of the medication_events row this audit entry describes */
    val eventUuid: String,
    /** e.g. MARK_TAKEN, REVERT, SNOOZE, IMPORT_HISTORICAL */
    val action: String,
    val oldStatus: String,
    val newStatus: String,
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String = ""
)

/** Audit action names. */
object AuditAction {
    const val MARK_TAKEN = "MARK_TAKEN"
    /** Recorded after the scheduled day/window as a correction ("Added later"). */
    const val MARK_TAKEN_LATE = "MARK_TAKEN_LATE"
    const val REVERT = "REVERT"
    const val SNOOZE = "SNOOZE"
    const val IMPORT_HISTORICAL = "IMPORT_HISTORICAL"
    const val MISS = "MISS"
}
