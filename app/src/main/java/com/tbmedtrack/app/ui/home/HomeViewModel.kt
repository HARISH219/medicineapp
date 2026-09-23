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
    val notScheduledToday: List<NotScheduledInfo> = emptyList()
)

data class NotScheduledInfo(
    val medicineName: String,
    val nextDateLabel: String,
    val nextTablets: Int
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.medRepository(app)
    private val settings = ServiceLocator.settingsRepository(app)

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
            // Next upcoming or currently-due event
            val next = events.firstOrNull { ev ->
                ev.doses.any { it.status == DoseStatus.SCHEDULED } &&
                    ev.scheduledMillis + 60 * 60_000L >= now
            } ?: events.firstOrNull { ev -> ev.doses.any { it.status == DoseStatus.SCHEDULED } }

            val due = next != null && now >= next.scheduledMillis &&
                now <= next.scheduledMillis + 60 * 60_000L

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
            val pendingEvent = events.firstOrNull { ev ->
                now >= ev.scheduledMillis && ev.doses.any { it.status != DoseStatus.TAKEN }
            }
            val actionRequired = pendingEvent != null

            val eventsTotal = events.size
            val eventsCompleted = events.count { it.allTaken }
            val todayComplete = eventsTotal > 0 && eventsCompleted == eventsTotal

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
                notScheduledToday = notScheduled
            )
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
