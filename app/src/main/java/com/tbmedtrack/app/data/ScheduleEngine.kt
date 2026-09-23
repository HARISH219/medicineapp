package com.tbmedtrack.app.data

import com.tbmedtrack.app.data.db.Medicine
import com.tbmedtrack.app.data.db.MedicationPhase
import com.tbmedtrack.app.data.db.PhaseScheduleType
import java.time.LocalDate

/**
 * Reusable, UI-independent dosing engine. Given a medicine, its phases, and a date, it returns
 * exactly whether a dose is scheduled, how many tablets, which phase, and the treatment day.
 * The prescription lives in data (phases) — never hard-coded in the UI — so it can change later.
 *
 * Supports DAILY and WEEKLY_DAYS schedule types within treatment-day-range phases. Falls back
 * gracefully for medicines that have no phases (treated as a plain daily 1-dose medicine).
 */
object ScheduleEngine {

    data class Result(
        val medicineName: String,
        val scheduled: Boolean,
        val tablets: Int,
        val phaseName: String,
        val treatmentDay: Int,
        val phaseDayCount: Int?,   // e.g. 14 for a bounded phase, null if open-ended
        val reason: String
    )

    /** 1-based treatment day for [date] given the medicine's start; 0 if before start. */
    fun treatmentDay(medicine: Medicine, date: LocalDate): Int {
        val d = date.toEpochDay() - medicine.startDate + 1
        return if (d < 1) 0 else d.toInt()
    }

    /** Evaluate the schedule for a phased medicine on a specific date. */
    fun evaluate(medicine: Medicine, phases: List<MedicationPhase>, date: LocalDate): Result {
        val day = treatmentDay(medicine, date)
        // Before treatment start, or medicine ended.
        if (day < 1) {
            return Result(medicine.name, false, 0, "", day, null, "Before treatment start")
        }
        medicine.endDate?.let {
            if (date.toEpochDay() > it) {
                return Result(medicine.name, false, 0, "", day, null, "Treatment ended")
            }
        }

        val phase = phases.firstOrNull { p ->
            day >= p.startDay && (p.endDay == null || day <= p.endDay)
        } ?: return Result(medicine.name, false, 0, "", day, null, "No active phase")

        val phaseDayCount = phase.endDay?.let { it - phase.startDay + 1 }

        return when (phase.scheduleType) {
            PhaseScheduleType.DAILY -> Result(
                medicine.name, true, phase.tabletsPerDose, phase.phaseName, day, phaseDayCount,
                "Scheduled"
            )
            PhaseScheduleType.WEEKLY_DAYS -> {
                val iso = date.dayOfWeek.value // 1=Mon..7=Sun
                val days = parseDays(phase.daysOfWeek)
                if (iso in days) {
                    Result(medicine.name, true, phase.tabletsPerDose, phase.phaseName, day, phaseDayCount, "Scheduled")
                } else {
                    Result(medicine.name, false, 0, phase.phaseName, day, phaseDayCount, "Not scheduled today")
                }
            }
            else -> Result(medicine.name, true, phase.tabletsPerDose, phase.phaseName, day, phaseDayCount, "Scheduled")
        }
    }

    data class NextDose(val date: LocalDate, val tablets: Int, val phaseName: String)

    /** The next date at/after [fromExclusive]+1 on which this medicine is scheduled. */
    fun nextScheduledDose(
        medicine: Medicine,
        phases: List<MedicationPhase>,
        fromDate: LocalDate,
        horizonDays: Int = 60
    ): NextDose? {
        var d = fromDate.plusDays(1)
        repeat(horizonDays) {
            val r = evaluate(medicine, phases, d)
            if (r.scheduled) return NextDose(d, r.tablets, r.phaseName)
            d = d.plusDays(1)
        }
        return null
    }

    private fun parseDays(csv: String): Set<Int> =
        csv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

    fun daysLabel(csv: String): String {
        val names = mapOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")
        return parseDays(csv).sorted().mapNotNull { names[it] }.joinToString(" • ")
    }
}
