package com.tbmedtrack.app.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.data.model.DoseEvent
import com.tbmedtrack.app.data.model.ScheduledDose
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

data class HomeUiState(
    val greeting: String = "",
    val dateLabel: String = "",
    val totalDoses: Int = 0,
    val takenDoses: Int = 0,
    val progressFraction: Float = 0f,
    val remainingText: String = "",
    val events: List<DoseEvent> = emptyList(),
    val nextDose: DoseEvent? = null,
    val nextDoseCountdown: String = "",
    val nextDoseDue: Boolean = false,
    val loading: Boolean = true,
    val treatmentDay: Int = 0,
    val hasMedicines: Boolean = true,
    /** the primary morning required combination, if any */
    val morningCombination: DoseEvent? = null,
    /** the night / bedtime dose event, if any */
    val nightEvent: DoseEvent? = null,
    /** true when any dose scheduled today (at/after its time) is not yet recorded */
    val actionRequired: Boolean = false,
    /** the specific pending event needing action right now, if any */
    val pendingEvent: DoseEvent? = null,
    val combinationCount: Int = 0,
    /** completed vs total medication *events* today (morning counts as one, night one) */
    val eventsCompleted: Int = 0,
    val eventsTotal: Int = 0,
    val todayComplete: Boolean = false,
    /** phased medicines NOT scheduled today, with their next dose info */
    val notScheduledToday: List<NotScheduledInfo> = emptyList(),
    /** food → medicine gap state for the home Food Timing card (null = no gap configured today) */
    val food: FoodUiState? = null,
    /**
     * Overdue events: scheduled time has passed and NOT all taken. Shown at the top, red, and
     * remain until explicitly recorded. Earliest first.
     */
    val overdueEvents: List<DoseEvent> = emptyList(),
    /**
     * The single event that should get primary focus = the EARLIEST scheduled event today that
     * is not fully taken (overdue if past, due if within the window, else the next upcoming).
     * Never a future event while an earlier one is incomplete.
     */
    val primaryEvent: DoseEvent? = null,
    /** presentation state of [primaryEvent] */
    val primaryState: DoseUrgency = DoseUrgency.NONE
)

/** Urgency buckets used for card color + ordering. */
enum class DoseUrgency { NONE, UPCOMING, DUE, OVERDUE, TAKEN }

/** State for the home "Food Timing" card. */
data class FoodUiState(
    /** epoch millis of the most recent food event, or null if none recorded */
    val lastFoodMillis: Long?,
    /** earliest time the next pending dose may be taken, given the latest food + gap */
    val medicineAvailableMillis: Long?,
    /** true when the waiting period is complete (medicine available now) */
    val available: Boolean,
    /** true when there is a pending food-gapped dose that food would affect */
    val hasPendingGapDose: Boolean,
    /** the default gap minutes (for display) */
    val gapMinutes: Int,
    /** label of the medicine/event the food gap is associated with, e.g. "10:00 AM Morning medicine" */
    val associatedLabel: String? = null,
    /** true if the associated dose is past its scheduled time and not taken (overdue) */
    val associatedOverdue: Boolean = false
)

data class NotScheduledInfo(
    val medicineName: String,
    val nextDateLabel: String,
    val nextTablets: Int
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.medRepository(app)
    private val settings = ServiceLocator.settingsRepository(app)
    private val foodRepo = ServiceLocator.foodRepository(app)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val today = ScheduleUtil.today()
            val doses = repo.getDosesForDay(today)
            val events = repo.groupIntoEvents(doses)

            // Phased medicines that are NOT scheduled today -> show next-dose info.
            val medsWithSchedules = repo.getAllMedicinesWithSchedules()
            val scheduledMedIds = doses.map { it.medicineId }.toSet()
            val notScheduled = mutableListOf<NotScheduledInfo>()
            for (mws in medsWithSchedules) {
                if (!mws.medicine.active) continue
                if (mws.medicine.id in scheduledMedIds) continue
                val phases = repo.phasesFor(mws.medicine.id)
                if (phases.isEmpty()) continue // only phased meds get the not-scheduled card
                val started = today.toEpochDay() >= mws.medicine.startDate
                if (!started) continue
                val next = repo.nextScheduledDoseFor(mws.medicine.id, today) ?: continue
                notScheduled += NotScheduledInfo(
                    medicineName = mws.medicine.name,
                    nextDateLabel = next.date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() },
                    nextTablets = next.tablets
                )
            }
            val total = doses.size
            val taken = doses.count { it.status == DoseStatus.TAKEN }
            val remaining = total - taken
            val hasMeds = repo.getAllMedicinesWithSchedules().isNotEmpty()

            val now = System.currentTimeMillis()
            val dueWindowMs = 60 * 60_000L // "due" grace window after scheduled time

            // Events sorted by scheduled time (ascending).
            val ordered = events.sortedBy { it.scheduledMillis }
            // Not-yet-fully-taken events.
            val pending = ordered.filter { !it.allTaken }

            // OVERDUE = past its scheduled time (beyond the due window) and not fully taken.
            val overdue = pending.filter { now > it.scheduledMillis + dueWindowMs }
            // DUE = scheduled time reached, still within the due window, not fully taken.
            val dueNow = pending.filter { now in it.scheduledMillis..(it.scheduledMillis + dueWindowMs) }

            // PRIMARY = the EARLIEST scheduled event that isn't fully taken. This guarantees an
            // earlier missed dose always takes priority over any later/future dose.
            val primary = pending.minByOrNull { it.scheduledMillis }
            val primaryState = when {
                primary == null -> DoseUrgency.NONE
                now > primary.scheduledMillis + dueWindowMs -> DoseUrgency.OVERDUE
                now >= primary.scheduledMillis -> DoseUrgency.DUE
                else -> DoseUrgency.UPCOMING
            }

            // The hero card uses the primary event (earliest untaken), NOT a blind "next future".
            val next = primary ?: ordered.lastOrNull()
            val due = primaryState == DoseUrgency.DUE

            val treatmentStart = resolveTreatmentStart()
            val treatmentDay = if (treatmentStart == null) 0
            else (today.toEpochDay() - treatmentStart + 1).toInt().coerceAtLeast(0)

            // Primary morning combination: the first event scheduled in the 9:00-12:00 window,
            // otherwise the earliest event of the day.
            val morning = events.firstOrNull { it.timeMinutes in (9 * 60)..(12 * 60) }
                ?: events.minByOrNull { it.timeMinutes }
            // Night event: latest event after 17:00 that isn't the morning one.
            val night = events.filter { it.timeMinutes >= 17 * 60 && it != morning }
                .maxByOrNull { it.timeMinutes }

            // A dose event is "pending action" if its time has arrived and not all taken.
            val pendingEvent = (overdue + dueNow).minByOrNull { it.scheduledMillis }
            val actionRequired = pendingEvent != null

            val eventsTotal = events.size
            val eventsCompleted = events.count { it.allTaken }
            val todayComplete = eventsTotal > 0 && eventsCompleted == eventsTotal

            val food = computeFoodState(doses, ordered)

            _state.value = HomeUiState(
                greeting = greeting(),
                dateLabel = today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() } +
                    ", " + today.month.name.lowercase().replaceFirstChar { it.uppercase() } + " " + today.dayOfMonth,
                totalDoses = total,
                takenDoses = taken,
                progressFraction = if (total == 0) 0f else taken.toFloat() / total,
                remainingText = when {
                    total == 0 -> "No doses scheduled today"
                    remaining == 0 -> "All scheduled doses completed ✓"
                    remaining == 1 -> "1 dose remaining"
                    else -> "$remaining doses remaining"
                },
                events = events,
                nextDose = next,
                nextDoseCountdown = next?.let { countdown(it.scheduledMillis, now) } ?: "",
                nextDoseDue = due,
                loading = false,
                treatmentDay = treatmentDay,
                hasMedicines = hasMeds,
                morningCombination = morning,
                nightEvent = night,
                actionRequired = actionRequired,
                pendingEvent = pendingEvent,
                combinationCount = morning?.totalCount ?: 0,
                eventsCompleted = eventsCompleted,
                eventsTotal = eventsTotal,
                todayComplete = todayComplete,
                notScheduledToday = notScheduled,
                food = food,
                overdueEvents = overdue,
                primaryEvent = primary,
                primaryState = primaryState
            )
        }
    }

    /** Human label for the medicine/event a food gap attaches to, e.g. "10:00 AM Morning medicine". */
    private fun eventLabel(event: DoseEvent): String {
        val part = when {
            event.timeMinutes < 12 * 60 -> "Morning medicine"
            event.timeMinutes < 17 * 60 -> "Afternoon medicine"
            else -> "Night medicine"
        }
        return "${ScheduleUtil.formatTime(event.timeMinutes)} $part"
    }

    /**
     * Build the Food Timing card state.
     *
     * Association rule (spec #7-#10): the food gap attaches to the EARLIEST scheduled event today
     * that is not yet taken — i.e. an overdue/past dose has priority over any future dose. It is
     * NEVER blindly attached to the next future medicine while an earlier one is incomplete.
     *
     *   1. Among not-taken events whose scheduled time has PASSED, pick the earliest -> overdue.
     *   2. Otherwise pick the earliest not-taken event (the current/next active dose).
     * Then compute the food-adjusted eligible time for that specific event.
     *
     * [ordered] is today's events sorted by scheduled time.
     */
    private suspend fun computeFoodState(
        doses: List<com.tbmedtrack.app.data.model.ScheduledDose>,
        ordered: List<DoseEvent>
    ): FoodUiState {
        val defaultGap = runCatching { settings.settings.first().defaultFoodGapMinutes }.getOrDefault(120)
        val lastFood = runCatching { foodRepo.latestFood() }.getOrNull()
        val now = System.currentTimeMillis()

        val pending = ordered.filter { !it.allTaken }
        // Priority: earliest PAST-and-not-taken event; else earliest pending event.
        val associated = pending.filter { now > it.scheduledMillis }.minByOrNull { it.scheduledMillis }
            ?: pending.minByOrNull { it.scheduledMillis }

        if (associated == null) {
            return FoodUiState(
                lastFoodMillis = lastFood?.foodTimeMillis,
                medicineAvailableMillis = null,
                available = true,
                hasPendingGapDose = false,
                gapMinutes = defaultGap,
                associatedLabel = null,
                associatedOverdue = false
            )
        }

        // Food-adjusted eligible time for the ASSOCIATED event only (use its most restrictive med).
        var eligible: Long? = null
        var hasGap = false
        for (dose in associated.doses) {
            if (dose.status == com.tbmedtrack.app.data.db.DoseStatus.TAKEN) continue
            val med = repo.getMedicine(dose.medicineId) ?: continue
            val timing = runCatching { foodRepo.timingFor(med, dose.scheduledMillis) }.getOrNull() ?: continue
            hasGap = true
            if (eligible == null || timing.eligibleMillis > eligible!!) eligible = timing.eligibleMillis
        }
        val overdue = now > associated.scheduledMillis + 60 * 60_000L

        return FoodUiState(
            lastFoodMillis = lastFood?.foodTimeMillis,
            medicineAvailableMillis = eligible,
            available = eligible != null && now >= eligible!!,
            hasPendingGapDose = hasGap,
            gapMinutes = defaultGap,
            associatedLabel = eventLabel(associated),
            associatedOverdue = overdue
        )
    }

    /** Record "I have eaten" now, then recompute the food-gap alarm chain and refresh. */
    fun recordFood() {
        viewModelScope.launch {
            foodRepo.recordFood()
            ServiceLocator.foodGapScheduler(getApplication()).rescheduleForToday()
            // Food is part of the primary-authored read-only monitor snapshot.
            ServiceLocator.syncManager(getApplication()).queue()
            com.tbmedtrack.app.widget.MedTrackWidgetProvider.updateAllWidgets(getApplication())
            refresh()
        }
    }

    private suspend fun resolveTreatmentStart(): Long? {
        val s = settings.settings.first()
        if (s.treatmentStartDay > 0) return s.treatmentStartDay
        // fall back to earliest medicine start date
        val meds = repo.getAllMedicinesWithSchedules()
        return meds.minOfOrNull { it.medicine.startDate }
    }

    fun markEventTaken(event: DoseEvent) {
        viewModelScope.launch {
            repo.markEventTaken(event.epochDay, event.timeMinutes)
            event.doses.forEach {
                com.tbmedtrack.app.reminder.NotificationHelper.cancel(getApplication(), it.scheduledMillis)
            }
            ServiceLocator.criticalAlarmScheduler(getApplication())
                .cancelEventChain(event.epochDay, event.timeMinutes, event.scheduledMillis)
            ServiceLocator.syncManager(getApplication()).queue()
            com.tbmedtrack.app.widget.MedTrackWidgetProvider.updateAllWidgets(getApplication())
            refresh()
        }
    }

    /**
     * "I already took it" — record an event as taken at an explicit actual time. When the actual
     * time is earlier than scheduled, history shows TAKEN EARLY; the stored take time is the real
     * time (never the scheduled time). Cancels reminders/critical for the event and syncs.
     */
    fun markEventTakenEarly(event: DoseEvent, actualTakenAt: Long) {
        viewModelScope.launch {
            repo.markEventTakenAt(event.epochDay, event.timeMinutes, actualTakenAt)
            event.doses.forEach {
                com.tbmedtrack.app.reminder.NotificationHelper.cancel(getApplication(), it.scheduledMillis)
            }
            ServiceLocator.criticalAlarmScheduler(getApplication())
                .cancelEventChain(event.epochDay, event.timeMinutes, event.scheduledMillis)
            ServiceLocator.syncManager(getApplication()).queue()
            com.tbmedtrack.app.widget.MedTrackWidgetProvider.updateAllWidgets(getApplication())
            refresh()
        }
    }

    fun markDoseTaken(dose: ScheduledDose) {
        viewModelScope.launch {
            repo.markTaken(dose)
            com.tbmedtrack.app.reminder.NotificationHelper.cancel(getApplication(), dose.scheduledMillis)
            ServiceLocator.syncManager(getApplication()).queue()
            com.tbmedtrack.app.widget.MedTrackWidgetProvider.updateAllWidgets(getApplication())
            refresh()
        }
    }

    /**
     * Revert an accidentally-taken dose event back to NOT RECORDED (PENDING). Keeps the audit
     * trail, syncs the correction, and re-arms the reminder chain if the dose window is open.
     */
    fun revertEvent(event: DoseEvent) {
        viewModelScope.launch {
            val reverted = repo.revertEvent(event.epochDay, event.timeMinutes)
            if (reverted) {
                // Re-arm escalation from the current step if we are still within the day.
                ServiceLocator.criticalAlarmScheduler(getApplication()).rescheduleTodayAndFuture()
                ServiceLocator.syncManager(getApplication()).queue()
            }
            com.tbmedtrack.app.widget.MedTrackWidgetProvider.updateAllWidgets(getApplication())
            refresh()
        }
    }

    private fun greeting(): String {
        val h = LocalTime.now(ScheduleUtil.zone()).hour
        return when {
            h < 12 -> "Good morning ☀️"
            h < 17 -> "Good afternoon ☀️"
            else -> "Good evening 🌙"
        }
    }

    private fun countdown(target: Long, now: Long): String {
        val diff = target - now
        if (diff <= 0) return "Due now"
        val minutes = diff / 60000L
        return when {
            minutes < 60 -> "In $minutes min"
            minutes < 24 * 60 -> "In ${minutes / 60}h ${minutes % 60}m"
            else -> "In ${minutes / (60 * 24)} days"
        }
    }
}
