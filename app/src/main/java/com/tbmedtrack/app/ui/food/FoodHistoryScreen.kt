package com.tbmedtrack.app.ui.food

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.ui.components.EmptyState
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.theme.DarkOnSurfaceMuted
import com.tbmedtrack.app.ui.theme.DueOrange
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class FoodHistoryRow(
    val foodMillis: Long,
    val eligibleMillis: Long,
    val gapMinutes: Int
)

data class FoodHistoryUiState(
    val byDay: Map<Long, List<FoodHistoryRow>> = emptyMap(),
    val loading: Boolean = true
)

class FoodHistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val foodRepo = ServiceLocator.foodRepository(app)
    private val settings = ServiceLocator.settingsRepository(app)

    private val _state = MutableStateFlow(FoodHistoryUiState())
    val state: StateFlow<FoodHistoryUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            val gap = runCatching { settings.settings.first().defaultFoodGapMinutes }.getOrDefault(120)
            val events = foodRepo.recentFoodEvents(90)
            val rows = events.map {
                FoodHistoryRow(
                    foodMillis = it.foodTimeMillis,
                    eligibleMillis = it.foodTimeMillis + gap * 60_000L,
                    gapMinutes = gap
                )
            }
            _state.value = FoodHistoryUiState(rows.groupBy { it.foodMillis.toLocalEpochDay() }, false)
        }
    }
}

private fun Long.toLocalEpochDay(): Long =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

private val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d")

@Composable
fun FoodHistoryScreen(vm: FoodHistoryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    if (!state.loading && state.byDay.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Restaurant,
            title = "No food recorded yet",
            subtitle = "Tap “I have eaten” on the home screen to start tracking your food → medicine gap."
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 12.dp,
            bottom = com.tbmedtrack.app.ui.components.bottomNavContentPadding()
        )
    ) {
        val today = ScheduleUtil.today().toEpochDay()
        // Newest day first.
        val days = state.byDay.keys.sortedDescending()
        for (day in days) {
            val rows = state.byDay[day] ?: continue
            item(key = "h$day") {
                Text(
                    dayHeader(day, today),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            item(key = "c$day") {
                SectionCard {
                    rows.sortedByDescending { it.foodMillis }.forEachIndexed { i, row ->
                        if (i > 0) Spacer(Modifier.height(12.dp))
                        FoodRowView(row)
                    }
                }
            }
        }
    }
}

@Composable
private fun FoodRowView(row: FoodHistoryRow) {
    Column {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("🍽️", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Food recorded", style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
                Text(clock(row.foodMillis), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("💊", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Medicine eligible", style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
                Text(clock(row.eligibleMillis), style = MaterialTheme.typography.titleMedium, color = DueOrange, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun dayHeader(epochDay: Long, today: Long): String = when (epochDay) {
    today -> "TODAY"
    today - 1 -> "YESTERDAY"
    else -> LocalDate.ofEpochDay(epochDay).format(dateFmt)
}

private fun clock(millis: Long): String {
    val lt = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
    return ScheduleUtil.formatTime(lt.hour * 60 + lt.minute)
}
