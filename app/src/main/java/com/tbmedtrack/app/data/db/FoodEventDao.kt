package com.tbmedtrack.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FoodEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: FoodEvent): Long

    /** The most recent food event overall (used for the live "last food" state). */
    @Query("SELECT * FROM food_events ORDER BY foodTimeMillis DESC LIMIT 1")
    suspend fun latest(): FoodEvent?

    /**
     * The most recent food event at or before [beforeMillis]. The LATEST applicable food event
     * controls the waiting period (a newer meal resets the gap).
     */
    @Query("SELECT * FROM food_events WHERE foodTimeMillis <= :beforeMillis ORDER BY foodTimeMillis DESC LIMIT 1")
    suspend fun latestBefore(beforeMillis: Long): FoodEvent?

    /** Food events for a given epoch day, newest first. */
    @Query("SELECT * FROM food_events WHERE epochDay = :epochDay ORDER BY foodTimeMillis DESC")
    suspend fun forDay(epochDay: Long): List<FoodEvent>

    /** Recent food events across days, newest first (for the food history screen). */
    @Query("SELECT * FROM food_events ORDER BY foodTimeMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int = 60): List<FoodEvent>
}
