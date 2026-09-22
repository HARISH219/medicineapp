package com.tbmedtrack.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LogDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: MedicationLog): Long

    @Update
    suspend fun update(log: MedicationLog)

    @Query("SELECT * FROM medication_events WHERE id = :id")
    suspend fun getById(id: Long): MedicationLog?

    @Query("SELECT * FROM medication_events WHERE uuid = :uuid LIMIT 1")
    suspend fun getByUuid(uuid: String): MedicationLog?

    @Query(
        "SELECT * FROM medication_events " +
            "WHERE medicineId = :medicineId AND scheduleId = :scheduleId " +
            "AND scheduledDateTime = :scheduledDateTime LIMIT 1"
    )
    suspend fun findLog(medicineId: Long, scheduleId: Long, scheduledDateTime: Long): MedicationLog?

    @Query("SELECT * FROM medication_events WHERE scheduledEpochDay = :epochDay ORDER BY scheduledDateTime ASC")
    suspend fun getLogsForDay(epochDay: Long): List<MedicationLog>

    @Query("SELECT * FROM medication_events WHERE scheduledEpochDay = :epochDay ORDER BY scheduledDateTime ASC")
    fun observeLogsForDay(epochDay: Long): Flow<List<MedicationLog>>

    @Query("SELECT * FROM medication_events WHERE scheduledEpochDay BETWEEN :startDay AND :endDay ORDER BY scheduledDateTime ASC")
    suspend fun getLogsBetween(startDay: Long, endDay: Long): List<MedicationLog>

    @Query("SELECT * FROM medication_events WHERE medicineId = :medicineId ORDER BY scheduledDateTime DESC")
    suspend fun getLogsForMedicine(medicineId: Long): List<MedicationLog>

    @Query("SELECT * FROM medication_events ORDER BY scheduledDateTime ASC")
    suspend fun getAllLogs(): List<MedicationLog>

    @Query("SELECT * FROM medication_events")
    fun observeAll(): Flow<List<MedicationLog>>

    @Query("DELETE FROM medication_events")
    suspend fun deleteAll()

    @Query("SELECT MIN(scheduledEpochDay) FROM medication_events")
    suspend fun earliestLoggedDay(): Long?

    // --- sync ---

    @Query("SELECT * FROM medication_events WHERE syncState = :state ORDER BY updatedAt ASC")
    suspend fun getBySyncState(state: String): List<MedicationLog>

    @Query("UPDATE medication_events SET syncState = :state WHERE uuid = :uuid")
    suspend fun setSyncState(uuid: String, state: String)
}
