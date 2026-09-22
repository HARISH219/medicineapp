package com.tbmedtrack.app.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.model.DaySummary
import com.tbmedtrack.app.data.model.DoseEvent
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

data class CalendarUiState(
    val yearMonth: YearMonth = YearMonth.now(),
    val summaries: Map<Long, DaySummary> = emptyMap(),
    val weekStartsMonday: Boolean = true,
    val selectedDay: Long? = null,
    val selectedEvents: List<DoseEvent> = emptyList(),
    val loading: Boolean = true
)

class CalendarViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.medRepository(app)
    private val settings = ServiceLocator.settingsRepository(app)

    private val _state = MutableStateFlow(CalendarUiState())
    val state: StateFlow<CalendarUiState> = _state.asStateFlow()

    fun load(month: YearMonth = _state.value.yearMonth) {
        viewModelScope.launch {
            val weekMonday = settings.settings.first().weekStartsMonday
            val summaries = repo.getMonthSummaries(month.year, month.monthValue)
            _state.value = _state.value.copy(
                yearMonth = month,
                summaries = summaries,
                weekStartsMonday = weekMonday,
                loading = false
            )
        }
    }

    fun nextMonth() = load(_state.value.yearMonth.plusMonths(1))
    fun prevMonth() = load(_state.value.yearMonth.minusMonths(1))

    fun selectDay(epochDay: Long) {
        viewModelScope.launch {
            val date = LocalDate.ofEpochDay(epochDay)
            val doses = repo.getDosesForDay(date)
            val events = repo.groupIntoEvents(doses)
            _state.value = _state.value.copy(selectedDay = epochDay, selectedEvents = events)
        }
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedDay = null, selectedEvents = emptyList())
    }
}
