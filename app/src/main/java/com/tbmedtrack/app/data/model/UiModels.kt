package com.tbmedtrack.app.data.model

import com.tbmedtrack.app.data.db.DoseStatus

/** A single medicine occurrence at a scheduled time on a specific day. */
data class ScheduledDose(
    val medicineId: Long,
    val scheduleId: Long,
    val medicineName: String,
    val doseText: String,
    val timeMinutes: Int,
    val scheduledMillis: Long,
    val epochDay: Long,
    val status: DoseStatus,
    val takenAtMillis: Long?,
    val snoozeCount: Int,
    val foodTiming: String,
    val notes: String,
    /** id of existing log row if present */
    val logId: Long?,
    val historical: Boolean = false,
    val takenTimePrecision: String = com.tbmedtrack.app.data.db.TakenTimePrecision.EXACT,
    /** exact tablet count scheduled today (from ScheduleEngine); 0 if not tablet-based */
    val tabletsScheduled: Int = 0,
    /** phase name active today, e.g. "Phase 1"; blank if the medicine isn't phased */
    val phaseName: String = "",
    /** 1-based treatment day for this medicine today; 0 if not phased/started */
    val treatmentDay: Int = 0,
    /** total days in the current bounded phase (e.g. 14), null if open-ended/none */
    val phaseDayCount: Int? = null
)

/**
 * Status as shown to the user. Distinct from the persisted [DoseStatus]: it separates
 * HISTORICAL (previously-taken import), PENDING (due/late today, not recorded), and
 * UPCOMING (scheduled later today or a future day) — so an unconfigured past day is never
 * shown as MISSED.
 */
enum class PresentationStatus { TAKEN, HISTORICAL, MISSED, PENDING, UPCOMING, SNOOZED }

/** Derive the user-facing status for a dose given the current time. */
fun ScheduledDose.presentation(nowMillis: Long = System.currentTimeMillis()): PresentationStatus =
    when (status) {
        DoseStatus.TAKEN -> if (historical) PresentationStatus.HISTORICAL else PresentationStatus.TAKEN
        DoseStatus.MISSED -> PresentationStatus.MISSED
        DoseStatus.SNOOZED -> PresentationStatus.SNOOZED
        DoseStatus.SKIPPED -> PresentationStatus.MISSED
        DoseStatus.REVERTED -> PresentationStatus.PENDING
        DoseStatus.SCHEDULED ->
            if (nowMillis >= scheduledMillis) PresentationStatus.PENDING
            else PresentationStatus.UPCOMING
    }

/** All doses that share the same clock time on a day, grouped into one dose event. */
data class DoseEvent(
    val timeMinutes: Int,
    val scheduledMillis: Long,
    val epochDay: Long,
    val doses: List<ScheduledDose>
) {
    val allTaken: Boolean get() = doses.isNotEmpty() && doses.all { it.status == DoseStatus.TAKEN }
    val anyMissed: Boolean get() = doses.any { it.status == DoseStatus.MISSED }
    val takenCount: Int get() = doses.count { it.status == DoseStatus.TAKEN }
    val totalCount: Int get() = doses.size

    /**
     * Number of distinct MEDICINES in this event.
     * NOTE: medicines != tablets. Each medicine may need multiple tablets.
     */
    val medicinesCount: Int get() = doses.map { it.medicineId }.distinct().size

    /**
     * Total number of TABLETS to swallow for this event, summed from each medicine's
     * actual scheduled tablet count (falls back to 1 tablet per medicine when a
     * medicine has no explicit tablet count). Never assume medicines == tablets.
     */
    val tabletsCount: Int get() = doses.sumOf { if (it.tabletsScheduled > 0) it.tabletsScheduled else 1 }
}

enum class DayAdherence { NONE, ALL_TAKEN, PARTIAL, MISSED }

data class DaySummary(
    val epochDay: Long,
    val scheduled: Int,
    val taken: Int,
    val missed: Int,
    val adherence: DayAdherence
)

data class Statistics(
    val scheduled: Int,
    val taken: Int,
    val missed: Int,
    val late: Int,
    val adherencePercent: Double,
    val currentStreak: Int,
    val bestStreak: Int,
    val daysTracked: Int,
    val completedDoseEvents: Int,
    val totalTaken: Int,
    val totalMissed: Int,
    val averageDelayMinutes: Int
)
