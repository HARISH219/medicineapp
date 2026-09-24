package com.tbmedtrack.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A "food eaten" event recorded on the MAIN device via the "I have eaten" button.
 *
 * Food events are a local / main-device feature: they drive the food → medicine gap timing
 * locally and are NEVER synced as an action to monitor devices (see the sync layer). Only the
 * resulting medication status may sync. The alarm calculation must work fully offline.
 */
@Entity(
    tableName = "food_events",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index("foodTimeMillis"),
        Index("epochDay")
    ]
)
data class FoodEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String = java.util.UUID.randomUUID().toString(),
    /** device that recorded the food (main device) */
    val deviceId: String = "",
    /** epoch millis the user actually ate (what the gap is measured from) */
    val foodTimeMillis: Long,
    /** epoch day of [foodTimeMillis] for fast per-day queries */
    val epochDay: Long,
    /** epoch millis the record was created (usually == foodTimeMillis for "I have eaten") */
    val recordedAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis()
)
