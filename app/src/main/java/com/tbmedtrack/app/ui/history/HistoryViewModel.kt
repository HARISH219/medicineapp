package com.tbmedtrack.app.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.model.DoseEvent
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HistoryUiState(
    val epochDay: Long = ScheduleUtil.today().toEpochDay(),
    val events: List<DoseEvent> = emptyList(),
    val loading: Boolean = true
)

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.medRepository(app)

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    fun load(epochDay: Long) {
        viewModelScope.launch {
            val date = LocalDate.ofEpochDay(epochDay)
            val doses = repo.getDosesForDay(date)
            val events = repo.groupIntoEvents(doses)
            _state.value = HistoryUiState(epochDay, events, false)
        }
    }
}
