package com.tbmedtrack.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicineDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedicine(medicine: Medicine): Long

    @Update
    suspend fun updateMedicine(medicine: Medicine)

    @Delete
    suspend fun deleteMedicine(medicine: Medicine)

    @Query("DELETE FROM medicines WHERE id = :id")
    suspend fun deleteMedicineById(id: Long)

    @Query("SELECT * FROM medicines WHERE id = :id")
    suspend fun getMedicine(id: Long): Medicine?

    @Transaction
    @Query("SELECT * FROM medicines WHERE id = :id")
    suspend fun getMedicineWithSchedules(id: Long): MedicineWithSchedules?

    @Transaction
    @Query("SELECT * FROM medicines ORDER BY name COLLATE NOCASE ASC")
    fun observeMedicinesWithSchedules(): Flow<List<MedicineWithSchedules>>

    @Transaction
    @Query("SELECT * FROM medicines")
    suspend fun getAllMedicinesWithSchedules(): List<MedicineWithSchedules>

    @Query("SELECT * FROM medicines")
    suspend fun getAllMedicines(): List<Medicine>

    @Query("UPDATE medicines SET active = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    // --- Schedules ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: DoseSchedule): Long

    @Update
    suspend fun updateSchedule(schedule: DoseSchedule)

    @Query("DELETE FROM dose_schedules WHERE medicineId = :medicineId")
    suspend fun deleteSchedulesForMedicine(medicineId: Long)

    @Query("SELECT * FROM dose_schedules WHERE medicineId = :medicineId")
    suspend fun getSchedulesForMedicine(medicineId: Long): List<DoseSchedule>

    @Query("SELECT * FROM dose_schedules WHERE enabled = 1")
    suspend fun getAllEnabledSchedules(): List<DoseSchedule>

    @Query("SELECT * FROM dose_schedules WHERE id = :id")
    suspend fun getSchedule(id: Long): DoseSchedule?
}
