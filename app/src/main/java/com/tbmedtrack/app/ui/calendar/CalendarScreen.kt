package com.tbmedtrack.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.model.DayAdherence
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.history.DayHistoryContent
import com.tbmedtrack.app.ui.theme.StatusMissed
import com.tbmedtrack.app.ui.theme.StatusNeutral
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.ui.theme.StatusUpcoming
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: CalendarViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    val sheetState = rememberModalBottomSheetState()

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.prevMonth() }) {
                Icon(Icons.Filled.ChevronLeft, "Previous month")
            }
            Text(
                "${state.yearMonth.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${state.yearMonth.year}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = { vm.nextMonth() }) {
                Icon(Icons.Filled.ChevronRight, "Next month")
            }
        }

        Spacer(Modifier.height(8.dp))
        MonthGrid(state) { day -> vm.selectDay(day) }
        Spacer(Modifier.height(16.dp))
        Legend()
        Spacer(Modifier.height(com.tbmedtrack.app.ui.components.bottomNavContentPadding()))
    }

    if (state.selectedDay != null) {
        ModalBottomSheet(
            onDismissRequest = { vm.clearSelection() },
            sheetState = sheetState
        ) {
            DayHistoryContent(
                epochDay = state.selectedDay!!,
                events = state.selectedEvents,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MonthGrid(state: CalendarUiState, onDay: (Long) -> Unit) {
    val headers = if (state.weekStartsMonday)
        listOf("M", "T", "W", "T", "F", "S", "S")
    else listOf("S", "M", "T", "W", "T", "F", "S")

    val firstOfMonth = state.yearMonth.atDay(1)
    // leading blanks
    val firstDow = firstOfMonth.dayOfWeek.value // 1=Mon..7=Sun
    val offset = if (state.weekStartsMonday) firstDow - 1 else firstDow % 7
    val daysInMonth = state.yearMonth.lengthOfMonth()

    SectionCard {
        Row(Modifier.fillMaxWidth()) {
            headers.forEach { h ->
                Text(
                    h, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val cells = offset + daysInMonth
        val rows = (cells + 6) / 7
        var dayCounter = 1
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val index = r * 7 + c
                    if (index < offset || dayCounter > daysInMonth) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = state.yearMonth.atDay(dayCounter)
                        val summary = state.summaries[date.toEpochDay()]
                        DayCell(
                            day = dayCounter,
                            adherence = summary?.adherence ?: DayAdherence.NONE,
                            isToday = date == LocalDate.now(),
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            onClick = { onDay(date.toEpochDay()) }
                        )
                        dayCounter++
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    adherence: DayAdherence,
    isToday: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val dot = adherenceColor(adherence)
    Box(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        }
    }
}

@Composable
private fun Legend() {
    SectionCard {
        Text("Adherence", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        LegendRow(StatusTaken, "All doses completed")
        LegendRow(StatusUpcoming, "Some doses incomplete")
        LegendRow(StatusMissed, "Missed doses")
        LegendRow(StatusNeutral.copy(alpha = 0.4f), "No schedule")
    }
}

@Composable
private fun LegendRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

fun adherenceColor(a: DayAdherence): Color = when (a) {
    DayAdherence.ALL_TAKEN -> StatusTaken
    DayAdherence.PARTIAL -> StatusUpcoming
    DayAdherence.MISSED -> StatusMissed
    DayAdherence.NONE -> StatusNeutral.copy(alpha = 0.35f)
}
