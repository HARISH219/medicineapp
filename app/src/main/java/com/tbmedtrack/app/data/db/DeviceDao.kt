package com.tbmedtrack.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: Device)

    @Update
    suspend fun update(device: Device)

    @Query("SELECT * FROM devices WHERE revoked = 0 ORDER BY isThisDevice DESC, createdAt ASC")
    fun observeDevices(): Flow<List<Device>>

    @Query("SELECT * FROM devices WHERE revoked = 0")
    suspend fun getDevices(): List<Device>

    @Query("SELECT * FROM devices WHERE isThisDevice = 1 LIMIT 1")
    suspend fun getThisDevice(): Device?

    @Query("SELECT * FROM devices WHERE isThisDevice = 1 LIMIT 1")
    fun observeThisDevice(): Flow<Device?>

    @Query("UPDATE devices SET revoked = 1 WHERE deviceId = :deviceId")
    suspend fun revoke(deviceId: String)

    @Query("SELECT * FROM devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun getDevice(deviceId: String): Device?
}
