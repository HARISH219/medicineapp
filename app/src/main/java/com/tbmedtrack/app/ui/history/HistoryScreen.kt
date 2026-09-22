package com.tbmedtrack.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.data.model.DoseEvent
import com.tbmedtrack.app.ui.components.EmptyState
import com.tbmedtrack.app.ui.components.statusVisual
import com.tbmedtrack.app.util.ScheduleUtil
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(epochDay: Long, vm: HistoryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(epochDay) { vm.load(epochDay) }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item {
            DayHistoryContent(state.epochDay, state.events)
        }
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy")

@Composable
fun DayHistoryContent(
    epochDay: Long,
    events: List<DoseEvent>,
    modifier: Modifier = Modifier
) {
    val date = LocalDate.ofEpochDay(epochDay)
    Column(modifier.fillMaxWidth()) {
        Text(date.format(dateFmt), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))

        if (events.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.EventNote,
                title = "No medication records",
                subtitle = "No doses were scheduled for this date."
            )
            return
        }

        events.forEachIndexed { i, event ->
            HistoryEventRow(event)
            if (i < events.lastIndex) {
                Divider(Modifier.padding(vertical = 12.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HistoryEventRow(event: DoseEvent) {
    Column {
        Text(
            ScheduleUtil.formatTime(event.timeMinutes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        event.doses.forEach { dose ->
            val visual = statusVisual(dose.status)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Icon(visual.icon, visual.label, tint = visual.color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("💊 ${dose.medicineName}", style = MaterialTheme.typography.bodyLarge)
                    val detail = when (dose.status) {
                        DoseStatus.TAKEN -> {
                            val t = dose.takenAtMillis?.let { formatClock(it) } ?: ""
                            val delay = dose.takenAtMillis?.let { (it - dose.scheduledMillis) / 60000L } ?: 0L
                            buildString {
                                append("Taken")
                                if (t.isNotEmpty()) append(" at $t")
                                if (delay in 1..600) append(" • $delay min late")
                            }
                        }
                        DoseStatus.MISSED -> "Missed — no dose recorded"
                        DoseStatus.SNOOZED -> "Snoozed (${dose.snoozeCount}x)"
                        DoseStatus.SKIPPED -> "Skipped"
                        DoseStatus.REVERTED -> "Not recorded (reverted)"
                        DoseStatus.SCHEDULED -> "Scheduled"
                    }
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = visual.color)
                }
            }
        }
    }
}

private fun formatClock(millis: Long): String {
    val fmt = DateTimeFormatter.ofPattern("hh:mm a")
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(fmt)
}
