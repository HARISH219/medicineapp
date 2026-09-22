package com.tbmedtrack.app.ui.setup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.RegimenSeeder
import com.tbmedtrack.app.data.db.TakenTimePrecision
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

data class SetupUiState(
    val treatmentStart: LocalDate = RegimenSeeder.TREATMENT_START,
    val importFrom: LocalDate = RegimenSeeder.TREATMENT_START,
    val importTo: LocalDate = ScheduleUtil.today().minusDays(1),
    val precision: String = TakenTimePrecision.UNKNOWN,
    val working: Boolean = false,
    val importedCount: Int = 0,
    val done: Boolean = false,
    val message: String? = null
)

/**
 * Drives first-setup: treatment start date, optional import of previously-taken doses over a
 * date range, then starts active reminders from the current day. Also reachable from Settings.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.medRepository(app)
    private val settings = ServiceLocator.settingsRepository(app)

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            if (s.treatmentStartDay > 0) {
                val start = LocalDate.ofEpochDay(s.treatmentStartDay)
                _state.value = _state.value.copy(treatmentStart = start, importFrom = start)
            }
        }
    }

    fun setTreatmentStart(date: LocalDate) {
        _state.value = _state.value.copy(treatmentStart = date, importFrom = date)
    }

    fun setImportRange(from: LocalDate, to: LocalDate) {
        _state.value = _state.value.copy(importFrom = from, importTo = to)
    }

    fun setPrecision(p: String) { _state.value = _state.value.copy(precision = p) }

    /** Save treatment start and begin active reminders from today (no import). */
    fun finishWithoutImport() {
        viewModelScope.launch {
            applyTreatmentStart()
            beginTrackingToday()
            _state.value = _state.value.copy(done = true, message = "Reminders will start from today.")
        }
    }

    /** Import the selected historical range as already-taken, then track from today. */
    fun importHistory() {
        val s = _state.value
        if (s.importTo.isBefore(s.importFrom)) {
            _state.value = s.copy(message = "End date is before start date."); return
        }
        _state.value = s.copy(working = true, message = null)
        viewModelScope.launch {
            applyTreatmentStart()
            val count = repo.importHistoricalRange(
                s.importFrom.toEpochDay(), s.importTo.toEpochDay(), s.precision
            )
            beginTrackingToday()
            ServiceLocator.syncManager(getApplication()).queue()
            _state.value = _state.value.copy(
                working = false, importedCount = count, done = true,
                message = "Previous medication history saved ($count doses)."
            )
        }
    }

    private suspend fun applyTreatmentStart() {
        settings.setTreatmentStartDay(_state.value.treatmentStart.toEpochDay())
    }

    /** Active tracking (and thus "missed" evaluation) begins today. */
    private suspend fun beginTrackingToday() {
        val today = ScheduleUtil.today().toEpochDay()
        settings.setTrackingStartDay(today)
        repo.trackingStartDay = today
        ServiceLocator.criticalAlarmScheduler(getApplication()).rescheduleTodayAndFuture()
        ServiceLocator.alarmScheduler(getApplication()).rescheduleAll()
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
