package com.tbmedtrack.app.widget

import android.content.Context
import com.tbmedtrack.app.R
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.data.model.ScheduledDose
import com.tbmedtrack.app.util.ScheduleUtil

/** The five widget presentation states. */
enum class WidgetPhase { UPCOMING, DUE, TAKEN, ALL_COMPLETE, OVERDUE }

/** Everything the widget needs to render, derived purely from stored medication data. */
data class WidgetState(
    val phase: WidgetPhase,
    val mascotRes: Int,
    /** dot drawable res for up to 4 doses (order = schedule time) */
    val dotRes: List<Int>,
    val statusText: String,
    val contentDescription: String
)

object WidgetStateProvider {

    /**
     * Compute the widget state from the real local database (today's doses). Never fabricates
     * status. When offline this still works because Room is the local source of truth.
     */
    suspend fun compute(context: Context): WidgetState {
        val repo = ServiceLocator.medRepository(context)
        val today = ScheduleUtil.today()
        val doses = repo.getDosesForDay(today).sortedBy { it.timeMinutes }
        val now = System.currentTimeMillis()

        if (doses.isEmpty()) {
            // Nothing scheduled today. If a phased medicine has a next dose, show it.
            val next = firstPhasedNextDose(context, today)
            return if (next != null) {
                WidgetState(
                    phase = WidgetPhase.UPCOMING,
                    mascotRes = R.drawable.mascot_sleepy,
                    dotRes = emptyList(),
                    statusText = "${next.name}: not scheduled · Next ${next.dayLabel} ${next.tablets} tabs",
                    contentDescription = "MedTrack. ${next.name} not scheduled today. Next dose ${next.dayLabel}, ${next.tablets} tablets."
                )
            } else {
                WidgetState(
                    phase = WidgetPhase.UPCOMING,
                    mascotRes = R.drawable.mascot_sleepy,
                    dotRes = emptyList(),
                    statusText = "No doses today",
                    contentDescription = "MedTrack. No medication doses scheduled today."
                )
            }
        }

        val total = doses.size
        val taken = doses.count { it.status == DoseStatus.TAKEN }
        val missed = doses.count { it.status == DoseStatus.MISSED }
        // "due" = time has arrived, not taken, not yet past the missed cutoff
        val due = doses.any {
            it.status == DoseStatus.SCHEDULED && now >= it.scheduledMillis
        }
        val allTaken = taken == total

        val phase = when {
            allTaken -> WidgetPhase.ALL_COMPLETE
            missed > 0 -> WidgetPhase.OVERDUE
            due -> WidgetPhase.DUE
            taken > 0 -> WidgetPhase.TAKEN
            else -> WidgetPhase.UPCOMING
        }

        val mascot = when (phase) {
            WidgetPhase.UPCOMING -> R.drawable.mascot_sleepy
            WidgetPhase.DUE -> R.drawable.mascot_due
            WidgetPhase.TAKEN -> R.drawable.mascot_happy
            WidgetPhase.ALL_COMPLETE -> R.drawable.mascot_celebrate
            WidgetPhase.OVERDUE -> R.drawable.mascot_concerned
        }

        val dots = doses.take(4).map { dotFor(it, now) }

        // Feature the primary tablet-based (phased) medicine's exact count when present.
        val featured = doses.firstOrNull { it.tabletsScheduled > 0 }
        val tabletLine = featured?.let {
            "${it.medicineName}: ${it.tabletsScheduled} ${if (it.tabletsScheduled == 1) "tab" else "tabs"}"
        }

        // Food → medicine gap (main-device only feature; the widget never shows an "I have eaten"
        // button and food events are not synced as actions). If a pending dose is still inside its
        // food-gap window, surface that instead of the normal due/taken status.
        val foodLine = foodGapLine(context, doses, now)

        val statusText = when {
            foodLine != null -> foodLine
            else -> when (phase) {
                WidgetPhase.ALL_COMPLETE -> "All done! ♡"
                WidgetPhase.OVERDUE -> "$taken / $total · check dose"
                WidgetPhase.DUE -> "Due now ♡"
                WidgetPhase.TAKEN -> "$taken / $total taken ♡"
                WidgetPhase.UPCOMING -> nextLabel(doses)
            }
        }
        // Put the exact tablet count first when available (never a generic total).
        val finalStatus = if (tabletLine != null && foodLine == null) "$tabletLine · $statusText" else statusText

        return WidgetState(phase, mascot, dots, finalStatus, describe(phase, taken, total, doses))
    }

    private data class NextInfo(val name: String, val dayLabel: String, val tablets: Int)

    /** Next scheduled dose for the first phased medicine that isn't scheduled today. */
    private suspend fun firstPhasedNextDose(context: Context, today: java.time.LocalDate): NextInfo? {
        val repo = ServiceLocator.medRepository(context)
        for (mws in repo.getAllMedicinesWithSchedules()) {
            if (!mws.medicine.active) continue
            if (repo.phasesFor(mws.medicine.id).isEmpty()) continue
            val next = repo.nextScheduledDoseFor(mws.medicine.id, today) ?: continue
            val day = next.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
            return NextInfo(mws.medicine.name, day, next.tablets)
        }
        return null
    }

    /**
     * Compact food-gap line for the widget, or null when no pending dose is food-blocked.
     * Shows "Food gap · Medicine in Nm" while waiting, or "Medicine available now" once eligible.
     */
    private suspend fun foodGapLine(context: Context, doses: List<ScheduledDose>, now: Long): String? {
        val repo = ServiceLocator.medRepository(context)
        val foodRepo = ServiceLocator.foodRepository(context)
        // Only relevant if a meal was recorded at all.
        if (foodRepo.latestFood() == null) return null
        var eligible: Long? = null
        for (dose in doses) {
            if (dose.status == DoseStatus.TAKEN) continue
            val med = repo.getMedicine(dose.medicineId) ?: continue
            val t = foodRepo.timingFor(med, dose.scheduledMillis) ?: continue
            if (!t.foodAdjusted) continue
            if (eligible == null || t.eligibleMillis > eligible!!) eligible = t.eligibleMillis
        }
        val e = eligible ?: return null
        return if (now >= e) "💊 Medicine available now"
        else {
            val mins = ((e - now) / 60000L).toInt().coerceAtLeast(0)
            if (mins >= 60) "🍽️ Food gap · in ${mins / 60}h ${mins % 60}m"
            else "🍽️ Food gap · in ${mins}m"
        }
    }

    private fun dotFor(dose: ScheduledDose, now: Long): Int = when {
        dose.status == DoseStatus.TAKEN -> R.drawable.dot_done
        dose.status == DoseStatus.MISSED -> R.drawable.dot_overdue
        dose.status == DoseStatus.SCHEDULED && now >= dose.scheduledMillis -> R.drawable.dot_due
        else -> R.drawable.dot_upcoming
    }

    private fun nextLabel(doses: List<ScheduledDose>): String {
        val next = doses.firstOrNull { it.status == DoseStatus.SCHEDULED } ?: return "All set ♡"
        return "Next · ${ScheduleUtil.formatTime(next.timeMinutes)}"
    }

    private fun describe(phase: WidgetPhase, taken: Int, total: Int, doses: List<ScheduledDose>): String {
        val base = "MedTrack. $taken of $total medication doses recorded."
        val extra = when (phase) {
            WidgetPhase.ALL_COMPLETE -> " All doses complete."
            WidgetPhase.OVERDUE -> " A dose has not been recorded."
            WidgetPhase.DUE -> " A dose is due now."
            WidgetPhase.UPCOMING -> {
                val next = doses.firstOrNull { it.status == DoseStatus.SCHEDULED }
                if (next != null) " Next dose at ${ScheduleUtil.formatTime(next.timeMinutes)}." else ""
            }
            WidgetPhase.TAKEN -> ""
        }
        return base + extra
    }
}
