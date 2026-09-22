package com.tbmedtrack.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncOperationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(op: SyncOperation): Long

    @Query("SELECT * FROM sync_operations WHERE syncStatus = :status ORDER BY createdAt ASC")
    suspend fun byStatus(status: String): List<SyncOperation>

    @Query("SELECT * FROM sync_operations ORDER BY createdAt DESC")
    suspend fun all(): List<SyncOperation>

    @Query("UPDATE sync_operations SET syncStatus = :status WHERE operationId = :operationId")
    suspend fun setStatus(operationId: String, status: String)

    @Query("UPDATE sync_operations SET retryCount = retryCount + 1, syncStatus = :status WHERE operationId = :operationId")
    suspend fun incrementRetry(operationId: String, status: String)

    @Query("DELETE FROM sync_operations WHERE syncStatus = :status")
    suspend fun deleteByStatus(status: String)

    @Query("DELETE FROM sync_operations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM sync_operations WHERE syncStatus = :status")
    suspend fun countByStatus(status: String): Int
}
