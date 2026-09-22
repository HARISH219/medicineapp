package com.tbmedtrack.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recurring time at which a medicine should be taken.
 * A medicine can have multiple schedules (e.g. 08:00, 14:00, 20:00).
 */
@Entity(
    tableName = "dose_schedules",
    foreignKeys = [
        ForeignKey(
            entity = Medicine::class,
            parentColumns = ["id"],
            childColumns = ["medicineId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("medicineId")]
)
data class DoseSchedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    /** minutes since midnight, local time */
    val timeMinutes: Int,
    val frequency: Frequency = Frequency.EVERY_DAY,
    /** comma-separated ISO day numbers 1..7 (Mon..Sun) when SPECIFIC_DAYS */
    val daysOfWeek: String = "",
    /** interval in days when EVERY_X_DAYS */
    val intervalDays: Int = 1,
    /** anchor epoch day used for EVERY_X_DAYS calculations */
    val anchorEpochDay: Long = 0,
    val enabled: Boolean = true
)
