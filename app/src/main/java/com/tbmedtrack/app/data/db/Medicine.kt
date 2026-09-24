package com.tbmedtrack.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A medicine the user has entered. All fields are manually configured by the user.
 * The app never decides which drugs or doses are appropriate.
 */
@Entity(tableName = "medicines")
data class Medicine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** stable cross-device identifier */
    val uuid: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val dose: String,
    val unit: String,
    val type: String = "Tablet",
    val foodTiming: String = "Doesn't matter",
    val notes: String = "",
    val active: Boolean = true,
    val partOfTbRegimen: Boolean = true,
    /**
     * Special scheduling rule for combination regimens. Null = follow the medicine's
     * DoseSchedule rows normally. Recognized rules are defined in [ScheduleRule].
     */
    val scheduleRule: String? = null,
    /**
     * Required wait after eating before this medicine may be taken (the "food → medicine gap"),
     * in minutes. Per prescription, configurable per medicine — NOT a universal rule.
     *   -1 = inherit the app's global default food gap
     *    0 = no food gap (may be taken any time)
     *   >0 = explicit gap in minutes
     */
    val foodGapMinutes: Int = -1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** epoch day (LocalDate.toEpochDay) */
    val startDate: Long,
    /** epoch day, null = no end date */
    val endDate: Long? = null
)

/** Named special scheduling rules that the RegimenEngine understands. */
object ScheduleRule {
    /** All days for the first N treatment days, then only Mon/Wed/Fri. */
    const val BEDAQUILINE_14_THEN_MWF = "BEDAQUILINE_14_THEN_MWF"
}
