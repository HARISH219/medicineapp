package com.tbmedtrack.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.model.DoseEvent
import kotlinx.coroutines.launch
import com.tbmedtrack.app.ui.components.EmptyState
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.theme.StatusUpcomingContainer

@Composable
fun HomeScreen(
    onAddMedicine: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTreatment: () -> Unit,
    vm: HomeViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    // Event pending a revert confirmation (long-press on a TAKEN card).
    var revertTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<DoseEvent?>(null)
    }
    val snackbarHostState = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item {
            Column {
                Text(state.greeting, style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Your TB medication schedule",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    state.dateLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        if (state.actionRequired && state.pendingEvent != null) {
            item {
                ActionRequiredCard(
                    combination = state.pendingEvent!!,
                    onTaken = {
                        val ev = state.pendingEvent!!
                        vm.markEventTaken(ev)
                        showUndo(scope, snackbarHostState) { vm.revertEvent(ev) }
                    }
                )
            }
        }

        item { ProgressCard(state) }

        if (state.todayComplete && state.eventsTotal > 0) {
            item { TodayCompleteCard(state.eventsCompleted, state.eventsTotal) }
        }

        // Split dashboard: Morning TB combination + Night medicine.
        state.morningCombination?.let { morning ->
            item {
                DashboardEventCard(
                    title = "☀️ Morning TB medicines",
                    event = morning,
                    onTaken = { vm.markEventTaken(morning); showUndo(scope, snackbarHostState) { vm.revertEvent(morning) } },
                    onRequestRevert = { revertTarget = morning }
                )
            }
        }
        state.nightEvent?.let { night ->
            item {
                DashboardEventCard(
                    title = "🌙 Night medicine",
                    event = night,
                    onTaken = { vm.markEventTaken(night); showUndo(scope, snackbarHostState) { vm.revertEvent(night) } },
                    onRequestRevert = { revertTarget = night }
                )
            }
        }

        items(state.notScheduledToday, key = { it.medicineName }) { info ->
            NotScheduledCard(info)
        }

        if (state.combinationCount > 0) {
            item { CombinationCard(state.combinationCount, state.morningCombination) }
        }

        state.nextDose?.let { next ->
            item { NextDoseCard(state, next, onView = onOpenTreatment) }
        }

        item { QuickActions(onAddMedicine, onOpenCalendar, onOpenHistory, onOpenTreatment) }

        item {
            Text(
                "Full schedule",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (state.events.isEmpty() && !state.loading) {
            item {
                EmptyState(
                    icon = Icons.Outlined.EventBusy,
                    title = if (state.hasMedicines) "No doses scheduled today"
                    else "No medicines added yet",
                    subtitle = if (state.hasMedicines) "Enjoy your day — nothing is due today."
                    else "Add your first medicine to start tracking your TB treatment.",
                    actionLabel = if (state.hasMedicines) null else "Add medicine",
                    onAction = if (state.hasMedicines) null else onAddMedicine
                )
            }
        } else {
            items(state.events, key = { it.timeMinutes }) { event ->
                DoseEventCard(
                    event = event,
                    onMarkAllTaken = { vm.markEventTaken(event); showUndo(scope, snackbarHostState) { vm.revertEvent(event) } },
                    onMarkDoseTaken = { vm.markDoseTaken(it) }
                )
            }
        }
    }

        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
        )
    } // Box

    revertTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { revertTarget = null },
            title = { Text("Correct medication status?") },
            text = {
                Text(
                    "You currently marked this medicine as TAKEN. Do you want to change it back " +
                        "to NOT RECORDED?"
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.revertEvent(target); revertTarget = null
                }) { Text("Change status") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { revertTarget = null }) { Text("Cancel") }
            }
        )
    }
}

/** Show a transient Undo snackbar; performing Undo reverts the just-recorded event. */
private fun showUndo(
    scope: kotlinx.coroutines.CoroutineScope,
    host: androidx.compose.material3.SnackbarHostState,
    onUndo: () -> Unit
) {
    scope.launch {
        val result = host.showSnackbar(
            message = "Medicine recorded",
            actionLabel = "UNDO",
            duration = androidx.compose.material3.SnackbarDuration.Short
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onUndo()
    }
}

@Composable
private fun ActionRequiredCard(combination: DoseEvent, onTaken: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = com.tbmedtrack.app.ui.theme.StatusMissedContainer
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "🚨 ACTION REQUIRED",
                style = MaterialTheme.typography.labelLarge,
                color = com.tbmedtrack.app.ui.theme.StatusMissed,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${com.tbmedtrack.app.util.ScheduleUtil.formatTime(combination.timeMinutes)} medication has not been recorded.",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Today's combination: ${combination.totalCount} medicines",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onTaken,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = com.tbmedtrack.app.ui.theme.StatusMissed
                )
            ) { Text("MEDICINE TAKEN", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun NotScheduledCard(info: com.tbmedtrack.app.ui.home.NotScheduledInfo) {
    SectionCard {
        Text("💊 ${info.medicineName}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("NOT SCHEDULED TODAY",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("Next dose", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${info.nextDateLabel} • ${info.nextTablets} ${if (info.nextTablets == 1) "tablet" else "tablets"}",
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun CombinationCard(count: Int, combination: DoseEvent?) {
    SectionCard {
        Text(
            "TODAY'S COMBINATION",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "$count ${if (count == 1) "MEDICINE" else "MEDICINES"}",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        combination?.let { c ->
            Spacer(Modifier.height(8.dp))
            c.doses.forEach { d ->
                Text("💊 ${d.medicineName}  ${d.doseText}", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun ProgressCard(state: HomeUiState) {
    val progress by animateFloatAsState(
        targetValue = state.progressFraction,
        animationSpec = tween(700),
        label = "progress"
    )
    SectionCard {
        Text(
            "TODAY'S PROGRESS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${state.eventsCompleted} / ${state.eventsTotal} medication events completed",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(12.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            state.remainingText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.todayComplete)
                MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TodayCompleteCard(completed: Int, total: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = com.tbmedtrack.app.ui.theme.StatusTakenContainer
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("✓ TODAY'S MEDICATION COMPLETE",
                style = MaterialTheme.typography.titleMedium,
                color = com.tbmedtrack.app.ui.theme.StatusTaken,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("$completed / $total medication events completed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DashboardEventCard(
    title: String,
    event: DoseEvent,
    onTaken: () -> Unit,
    onRequestRevert: () -> Unit = {}
) {
    val now = System.currentTimeMillis()
    val allTaken = event.allTaken
    val takenAt = event.doses.mapNotNull { it.takenAtMillis }.maxOrNull()
    val historical = event.doses.any { it.historical }
    // Long-press a completed card to correct an accidental "taken".
    val cardModifier = if (allTaken) {
        Modifier.combinedClickable(onClick = {}, onLongClick = onRequestRevert)
    } else Modifier
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth().then(cardModifier),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
      androidx.compose.foundation.layout.Column(Modifier.padding(18.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            com.tbmedtrack.app.util.ScheduleUtil.formatTime(event.timeMinutes) +
                if (event.totalCount > 1) " • ${event.totalCount} medicines" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        when {
            allTaken && historical -> Text("✓ Recorded (previous history)",
                color = com.tbmedtrack.app.ui.theme.StatusTaken, fontWeight = FontWeight.SemiBold)
            allTaken -> {
                val t = takenAt?.let { formatClockShort(it) }
                Text(if (t != null) "✓ Taken at $t" else "✓ Taken",
                    color = com.tbmedtrack.app.ui.theme.StatusTaken, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("Long-press to correct if this was a mistake.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            now < event.scheduledMillis -> Text("○ Upcoming",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> {
                Text("🚨 Not recorded", color = com.tbmedtrack.app.ui.theme.StatusMissed,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onTaken, modifier = Modifier.fillMaxWidth()) {
                    Text("MEDICINE TAKEN", fontWeight = FontWeight.Bold)
                }
            }
        }
      }
    }
}

private fun formatClockShort(millis: Long): String {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("hh:mm a")
    return java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime().format(fmt)
}

@Composable
private fun NextDoseCard(state: HomeUiState, next: DoseEvent, onView: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (state.nextDoseDue) StatusUpcomingContainer
            else MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                if (state.nextDoseDue) "🔔 MEDICATION DUE" else "NEXT DOSE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "💊 ${next.totalCount} ${if (next.totalCount == 1) "medicine" else "medicines"}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(2.dp))
            Text(
                com.tbmedtrack.app.util.ScheduleUtil.formatTime(next.timeMinutes) +
                    " • " + state.nextDoseCountdown,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onView) { Text("View details") }
        }
    }
}

@Composable
private fun QuickActions(
    onAdd: () -> Unit,
    onCalendar: () -> Unit,
    onHistory: () -> Unit,
    onTreatment: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickAction("Add", Icons.Filled.Add, Modifier.weight(1f), onAdd)
        QuickAction("History", Icons.Filled.History, Modifier.weight(1f), onHistory)
        QuickAction("Calendar", Icons.Filled.CalendarMonth, Modifier.weight(1f), onCalendar)
        QuickAction("Treatment", Icons.Filled.MedicalServices, Modifier.weight(1f), onTreatment)
    }
}

@Composable
private fun QuickAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}
