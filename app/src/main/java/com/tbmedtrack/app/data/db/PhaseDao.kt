package com.tbmedtrack.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PhaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(phase: MedicationPhase): Long

    @Query("SELECT * FROM medication_phases WHERE medicineId = :medicineId ORDER BY orderIndex ASC, startDay ASC")
    suspend fun forMedicine(medicineId: Long): List<MedicationPhase>

    @Query("SELECT * FROM medication_phases ORDER BY medicineId, orderIndex ASC")
    suspend fun all(): List<MedicationPhase>

    @Query("DELETE FROM medication_phases WHERE medicineId = :medicineId")
    suspend fun deleteForMedicine(medicineId: Long)

    @Query("SELECT COUNT(*) FROM medication_phases WHERE medicineId = :medicineId")
    suspend fun countForMedicine(medicineId: Long): Int
}
