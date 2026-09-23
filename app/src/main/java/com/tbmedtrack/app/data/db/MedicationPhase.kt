package com.tbmedtrack.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A dosing phase for a medicine, expressed in treatment-day ranges. Phases let the exact
 * tablet count change over time (e.g. Bedaquiline: days 1-14 = 4 tablets daily, then
 * 2 tablets on Mon/Wed/Fri). The [ScheduleEngine] evaluates these; the UI never hard-codes them.
 */
@Entity(
    tableName = "medication_phases",
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
data class MedicationPhase(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val medicineId: Long,
    val phaseName: String,
    /** 1-based treatment day this phase begins (day 1 = treatment start date) */
    val startDay: Int,
    /** inclusive last treatment day, or null = open-ended */
    val endDay: Int? = null,
    /** tablets taken per scheduled dose during this phase */
    val tabletsPerDose: Int,
    /** DAILY or WEEKLY_DAYS */
    val scheduleType: String = PhaseScheduleType.DAILY,
    /** for WEEKLY_DAYS: comma-separated ISO day numbers 1..7 (Mon..Sun) */
    val daysOfWeek: String = "",
    /** display order */
    val orderIndex: Int = 0
)

object PhaseScheduleType {
    const val DAILY = "DAILY"
    const val WEEKLY_DAYS = "WEEKLY_DAYS"
}
