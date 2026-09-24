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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.model.DoseEvent
import com.tbmedtrack.app.data.model.HistoryStatus
import com.tbmedtrack.app.data.model.ScheduledDose
import com.tbmedtrack.app.data.model.historyStatus
import com.tbmedtrack.app.ui.components.EmptyState
import com.tbmedtrack.app.ui.theme.DarkOnSurfaceMuted
import com.tbmedtrack.app.ui.theme.StatusMissed
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.ui.theme.StatusUpcoming
import com.tbmedtrack.app.util.ScheduleUtil
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(epochDay: Long, vm: HistoryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(epochDay) { vm.load(epochDay) }

    // The event awaiting a past-dose confirmation dialog, if any.
    var confirmTarget by remember { mutableStateOf<DoseEvent?>(null) }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 12.dp,
            bottom = com.tbmedtrack.app.ui.components.bottomNavContentPadding()
        )
    ) {
        item {
            DayHistoryContent(
                epochDay = state.epochDay,
                events = state.events,
                onMarkPastTaken = { event -> confirmTarget = event }
            )
        }
    }

    confirmTarget?.let { event ->
        PastDoseConfirmDialog(
            event = event,
            onDismiss = { confirmTarget = null },
            onConfirm = { actualTakenAt ->
                vm.markPastTaken(event.epochDay, event.timeMinutes, actualTakenAt)
                confirmTarget = null
            }
        )
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("MMMM d, yyyy")

@Composable
fun DayHistoryContent(
    epochDay: Long,
    events: List<DoseEvent>,
    modifier: Modifier = Modifier,
    onMarkPastTaken: ((DoseEvent) -> Unit)? = null
) {
    val date = LocalDate.ofEpochDay(epochDay)
    val isPastOrToday = epochDay <= ScheduleUtil.today().toEpochDay()
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
            HistoryEventRow(
                event = event,
                allowMarkTaken = isPastOrToday && onMarkPastTaken != null,
                onMarkTaken = { onMarkPastTaken?.invoke(event) }
            )
            if (i < events.lastIndex) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun HistoryEventRow(
    event: DoseEvent,
    allowMarkTaken: Boolean,
    onMarkTaken: () -> Unit
) {
    Column {
        Text(
            ScheduleUtil.formatTime(event.timeMinutes),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(6.dp))
        var anyNotRecorded = false
        event.doses.forEach { dose ->
            val hs = dose.historyStatus()
            if (hs == HistoryStatus.NOT_RECORDED) anyNotRecorded = true
            val (color, label, detail) = statusFor(dose, hs)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(iconFor(hs), modifier = Modifier.width(28.dp))
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "💊 ${dose.medicineName}" +
                            if (dose.tabletsScheduled > 0)
                                "  •  ${dose.tabletsScheduled} ${if (dose.tabletsScheduled == 1) "tablet" else "tablets"}"
                            else "",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.SemiBold)
                    if (detail.isNotEmpty()) {
                        Text(detail, style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
                    }
                }
            }
        }
        // Offer correction only when something on this day was scheduled-but-not-recorded.
        if (allowMarkTaken && anyNotRecorded) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onMarkTaken, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.EventNote, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Mark as taken")
            }
        }
    }
}

private fun iconFor(hs: HistoryStatus): String = when (hs) {
    HistoryStatus.TAKEN -> "✓"
    HistoryStatus.TAKEN_EARLY -> "✓"
    HistoryStatus.TAKEN_LATE -> "✓"
    HistoryStatus.ADDED_LATER -> "📝"
    HistoryStatus.NOT_RECORDED -> "⚠"
    HistoryStatus.UPCOMING -> "○"
    HistoryStatus.SNOOZED -> "⏰"
}

/** Returns (color, statusLabel, detailLine) for a dose given its history status. */
private fun statusFor(dose: ScheduledDose, hs: HistoryStatus): Triple<androidx.compose.ui.graphics.Color, String, String> {
    return when (hs) {
        HistoryStatus.TAKEN -> {
            val t = dose.takenAtMillis?.let { "Taken at ${formatClock(it)}" } ?: "Taken"
            Triple(StatusTaken, "TAKEN", t)
        }
        HistoryStatus.TAKEN_EARLY -> {
            val t = dose.takenAtMillis?.let { formatClock(it) } ?: ""
            Triple(StatusTaken, "TAKEN EARLY",
                if (t.isNotEmpty()) "Taken at $t (before scheduled)" else "Taken before scheduled time")
        }
        HistoryStatus.TAKEN_LATE -> {
            val t = dose.takenAtMillis?.let { formatClock(it) } ?: ""
            val late = dose.takenAtMillis?.let { (it - dose.scheduledMillis) / 60000L } ?: 0L
            Triple(StatusUpcoming, "TAKEN LATE",
                if (t.isNotEmpty()) "Taken at $t • $late min late" else "Taken later than scheduled")
        }
        HistoryStatus.ADDED_LATER -> {
            val detail = when {
                dose.takenAtMillis != null -> "Added later • taken at ${formatClock(dose.takenAtMillis)}"
                else -> "Added later • actual time unknown"
            }
            Triple(StatusTaken, "ADDED LATER", detail)
        }
        HistoryStatus.NOT_RECORDED -> Triple(StatusMissed, "NOT RECORDED", "No dose recorded for this time")
        HistoryStatus.UPCOMING -> Triple(DarkOnSurfaceMuted, "UPCOMING", "Scheduled")
        HistoryStatus.SNOOZED -> Triple(StatusUpcoming, "SNOOZED", "Snoozed (${dose.snoozeCount}x)")
    }
}

/** Confirmation dialog for recording a past dose, with optional actual-time entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PastDoseConfirmDialog(
    event: DoseEvent,
    onDismiss: () -> Unit,
    onConfirm: (actualTakenAt: Long?) -> Unit
) {
    val date = LocalDate.ofEpochDay(event.epochDay)
    // Only offer to record doses that aren't already taken.
    val pending = event.doses.filter { it.historyStatus() == HistoryStatus.NOT_RECORDED }
    val medicineLine = pending.joinToString(", ") { it.medicineName }
    val tablets = pending.sumOf { if (it.tabletsScheduled > 0) it.tabletsScheduled else 1 }

    // Whether the user wants to specify the actual take time. Default: exact time unknown.
    var specifyTime by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(
        initialHour = event.timeMinutes / 60,
        initialMinute = event.timeMinutes % 60,
        is24Hour = false
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark this medication as taken?") },
        text = {
            Column {
                Info("Date", date.format(dateFmt))
                Info("Scheduled time", ScheduleUtil.formatTime(event.timeMinutes))
                if (medicineLine.isNotEmpty()) Info("Medicine", medicineLine)
                Info("Scheduled amount", "$tablets ${if (tablets == 1) "tablet" else "tablets"}")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !specifyTime,
                        onClick = { specifyTime = false },
                        label = { Text("Exact time unknown") }
                    )
                    FilterChip(
                        selected = specifyTime,
                        onClick = { specifyTime = true },
                        label = { Text("Enter actual time") }
                    )
                }
                if (specifyTime) {
                    Spacer(Modifier.height(12.dp))
                    TimePicker(state = timeState)
                    Text(
                        "This records when you actually took it, not the scheduled time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DarkOnSurfaceMuted
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "We'll mark it taken without a specific time (recorded later).",
                        style = MaterialTheme.typography.bodySmall,
                        color = DarkOnSurfaceMuted
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val actual = if (specifyTime) {
                    val minutes = timeState.hour * 60 + timeState.minute
                    ScheduleUtil.toEpochMillis(date, minutes)
                } else null
                onConfirm(actual)
            }) { Text("Mark as taken") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.width(130.dp), style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatClock(millis: Long): String {
    val fmt = DateTimeFormatter.ofPattern("hh:mm a")
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(fmt)
}
