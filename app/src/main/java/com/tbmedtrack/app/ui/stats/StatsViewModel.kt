package com.tbmedtrack.app.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.model.Statistics
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class StatsUiState(
    val monthLabel: String = "",
    val monthStats: Statistics? = null,
    val todayTaken: Int = 0,
    val todayTotal: Int = 0,
    val weekAdherence: Double = 0.0,
    val monthAdherence: Double = 0.0,
    val weeklyBars: List<Float> = emptyList(),
    val hasData: Boolean = false,
    val loading: Boolean = true
)

class StatsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.medRepository(app)

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val today = ScheduleUtil.today()
            val monthStart = today.withDayOfMonth(1)
            val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
            val monthStats = repo.computeStatistics(monthStart, monthEnd)

            val todaySummary = repo.getDaySummary(today)

            // Weekly adherence (last 7 days incl. today)
            val weekStart = today.minusDays(6)
            val weekStats = repo.computeStatistics(weekStart, today)

            // Weekly bars: adherence fraction per day for last 7 days
            val bars = mutableListOf<Float>()
            var d = weekStart
            while (!d.isAfter(today)) {
                val s = repo.getDaySummary(d)
                bars.add(if (s.scheduled == 0) 0f else s.taken.toFloat() / s.scheduled)
                d = d.plusDays(1)
            }

            _state.value = StatsUiState(
                monthLabel = today.month.name.lowercase().replaceFirstChar { it.uppercase() },
                monthStats = monthStats,
                todayTaken = todaySummary.taken,
                todayTotal = todaySummary.scheduled,
                weekAdherence = weekStats.adherencePercent,
                monthAdherence = monthStats.adherencePercent,
                weeklyBars = bars,
                hasData = monthStats.totalTaken > 0 || monthStats.totalMissed > 0 || monthStats.scheduled > 0,
                loading = false
            )
        }
    }
}
