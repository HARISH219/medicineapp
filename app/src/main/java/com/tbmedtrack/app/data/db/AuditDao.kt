package com.tbmedtrack.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(audit: MedicationEventAudit): Long

    @Query("SELECT * FROM medication_event_audit WHERE eventUuid = :eventUuid ORDER BY timestamp ASC")
    suspend fun forEvent(eventUuid: String): List<MedicationEventAudit>

    @Query("SELECT * FROM medication_event_audit ORDER BY timestamp DESC")
    suspend fun all(): List<MedicationEventAudit>

    @Query("DELETE FROM medication_event_audit")
    suspend fun deleteAll()
}
