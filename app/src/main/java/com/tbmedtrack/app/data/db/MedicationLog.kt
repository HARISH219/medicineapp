package com.tbmedtrack.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An append-oriented record of a single scheduled dose event for a medicine on a
 * given day/time. This is the `medication_events` table. Rows carry a stable UUID
 * and device/sync metadata so events can be synchronized across authorized devices
 * without overwriting an authoritative "taken" event.
 *
 * Historical rows are never rewritten by schedule edits.
 */
@Entity(
    tableName = "medication_events",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["medicineId", "scheduleId", "scheduledDateTime"], unique = true),
        Index("scheduledDateTime"),
        Index("syncState")
    ]
)
data class MedicationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** stable cross-device identifier */
    val uuid: String = java.util.UUID.randomUUID().toString(),
    /** id of the device that recorded/authored this event */
    val deviceId: String = "",
    val medicineId: Long,
    val scheduleId: Long,
    /** epoch millis of the scheduled dose time */
    val scheduledDateTime: Long,
    /** epoch day for fast per-day grouping */
    val scheduledEpochDay: Long,
    /** epoch millis when actually taken, null if not taken */
    val actualTakenDateTime: Long? = null,
    val status: DoseStatus = DoseStatus.SCHEDULED,
    val snoozeCount: Int = 0,
    /** snapshot of medicine name/dose at log time so history stays consistent */
    val medicineName: String = "",
    val doseText: String = "",
    val notes: String = "",
    /** true if this event was entered as previously-taken history at setup */
    val historical: Boolean = false,
    /**
     * Precision of [actualTakenDateTime]: EXACT (a real recorded time), APPROX (user gave an
     * approximate time), or UNKNOWN (taken that day but time not recorded — do not invent one).
     */
    val takenTimePrecision: String = TakenTimePrecision.EXACT,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
    /** local sync bookkeeping: see [SyncState] */
    val syncState: String = SyncState.PENDING
)

/** How precise a recorded taken-time is. */
object TakenTimePrecision {
    const val EXACT = "EXACT"
    const val APPROX = "APPROX"
    const val UNKNOWN = "UNKNOWN"
}

/** Sync bookkeeping states for an event row. */
object SyncState {
    const val PENDING = "PENDING"   // needs upload
    const val SYNCED = "SYNCED"     // uploaded / in cloud
    const val LOCAL_ONLY = "LOCAL_ONLY" // never sync (e.g. offline-only)
}
