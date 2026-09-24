package com.tbmedtrack.app.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.model.DoseEvent
import com.tbmedtrack.app.ui.components.EmptyState
import com.tbmedtrack.app.ui.components.MoonDecor
import com.tbmedtrack.app.ui.components.PillDecor
import com.tbmedtrack.app.ui.components.ProgressRing
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.components.SparkleDecor
import com.tbmedtrack.app.ui.components.SunDecor
import com.tbmedtrack.app.ui.theme.AccentPurple
import com.tbmedtrack.app.ui.theme.DarkOnSurfaceMuted
import com.tbmedtrack.app.ui.theme.DueOrange
import com.tbmedtrack.app.ui.theme.DueOrangeSoft
import com.tbmedtrack.app.ui.theme.StatusMissed
import com.tbmedtrack.app.ui.theme.StatusTaken
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onAddMedicine: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTreatment: () -> Unit,
    onOpenEvent: (DoseEvent) -> Unit = {},
    vm: HomeViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    val snackbarHostState = androidx.compose.runtime.remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var revertTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<DoseEvent?>(null)
    }
    // Event the user wants to record via "I already took it" (with a chosen actual time).
    var alreadyTookTarget by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<DoseEvent?>(null)
    }

    Box(Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(
                top = 14.dp,
                bottom = com.tbmedtrack.app.ui.components.bottomNavContentPadding()
            )
        ) {
            item { GreetingHeader(state) }

            state.nextDose?.let { next ->
                item {
                    NextDoseHero(
                        state = state,
                        event = next,
                        onMarkTaken = {
                            vm.markEventTaken(next)
                            showUndo(scope, snackbarHostState) { vm.revertEvent(next) }
                        }
                    )
                }
                if (!next.allTaken) {
                    item {
                        androidx.compose.material3.TextButton(
                            onClick = { alreadyTookTarget = next },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("I already took it (enter time)") }
                    }
                }
            }

            state.food?.let { food ->
                item {
                    FoodTimingCard(
                        food = food,
                        onEaten = { vm.recordFood() },
                        onOpenMedication = {
                            state.nextDose?.let { onOpenEvent(it) }
                        }
                    )
                }
            }

            if (state.eventsTotal > 0) {
                item { TodayProgressCard(state) }
            }

            if (state.events.isNotEmpty()) {
                item {
                    Text(
                        "Today's schedule",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                items(state.events, key = { it.timeMinutes }) { event ->
                    ScheduleEventCard(
                        event = event,
                        onClick = { onOpenEvent(event) },
                        onRequestRevert = { revertTarget = event }
                    )
                }
            }

            items(state.notScheduledToday, key = { it.medicineName }) { info ->
                NotScheduledCard(info)
            }

            if (state.events.isEmpty() && !state.loading) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.EventBusy,
                        title = if (state.hasMedicines) "Nothing due today"
                        else "No medicines added yet",
                        subtitle = if (state.hasMedicines) "Enjoy your day — take care of yourself."
                        else "Add your first medicine to start tracking your treatment.",
                        actionLabel = if (state.hasMedicines) null else "Add medicine",
                        onAction = if (state.hasMedicines) null else onAddMedicine
                    )
                }
            }

            item { MotivationalCard(state.todayComplete) }
        }

        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    revertTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { revertTarget = null },
            title = { Text("Correct medication status?") },
            text = {
                Text(
                    "You currently marked this as TAKEN. Change it back to NOT RECORDED?"
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

    alreadyTookTarget?.let { target ->
        AlreadyTookDialog(
            event = target,
            onDismiss = { alreadyTookTarget = null },
            onConfirm = { actualTakenAt ->
                vm.markEventTakenEarly(target, actualTakenAt)
                alreadyTookTarget = null
            }
        )
    }
}

/**
 * "Already took medicine?" dialog: pick which dose (fixed to the selected event) and WHEN it was
 * taken, then confirm. Stores the real take time (scheduled/taken/recorded stay distinct).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AlreadyTookDialog(
    event: DoseEvent,
    onDismiss: () -> Unit,
    onConfirm: (actualTakenAt: Long) -> Unit
) {
    val label = when {
        event.timeMinutes < 12 * 60 -> "Morning dose"
        event.timeMinutes < 17 * 60 -> "Afternoon dose"
        else -> "Night dose"
    }
    val nowLt = java.time.LocalTime.now(com.tbmedtrack.app.util.ScheduleUtil.zone())
    val timeState = androidx.compose.material3.rememberTimePickerState(
        initialHour = nowLt.hour, initialMinute = nowLt.minute, is24Hour = false
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("💊 Already took medicine?") },
        text = {
            Column {
                Text("Which dose?", style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
                Text(
                    "$label • ${com.tbmedtrack.app.util.ScheduleUtil.formatTime(event.timeMinutes)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(12.dp))
                Text("When did you take it?", style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TimePicker(state = timeState)
            }
        },
        confirmButton = {
            Button(onClick = {
                val minutes = timeState.hour * 60 + timeState.minute
                val actual = com.tbmedtrack.app.util.ScheduleUtil.toEpochMillis(
                    java.time.LocalDate.ofEpochDay(event.epochDay), minutes
                )
                onConfirm(actual)
            }) { Text("Confirm") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

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

/** "Good morning ☀️" + care line + date. */
@Composable
private fun GreetingHeader(state: HomeUiState) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                state.greeting,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Take care of yourself today.",
                style = MaterialTheme.typography.bodyLarge,
                color = DarkOnSurfaceMuted
            )
            Spacer(Modifier.height(2.dp))
            Text(
                state.dateLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = AccentPurple
            )
        }
        val isNight = state.greeting.contains("evening") || state.greeting.contains("🌙")
        Box(
            Modifier
                .size(46.dp)
                .background(Color(0x22FFFFFF), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isNight) MoonDecor(Modifier.size(30.dp)) else SunDecor(Modifier.size(34.dp))
        }
    }
}

/** Big orange NEXT DOSE hero: time, label, medicines•tablets, pill decor, Mark as taken. */
@Composable
private fun NextDoseHero(state: HomeUiState, event: DoseEvent, onMarkTaken: () -> Unit) {
    val timeLabel = com.tbmedtrack.app.util.ScheduleUtil.formatTime(event.timeMinutes)
    val partOfDay = when {
        event.timeMinutes < 12 * 60 -> "Morning medicines"
        event.timeMinutes < 17 * 60 -> "Afternoon medicines"
        else -> "Night medicines"
    }
    val meds = event.medicinesCount
    val tabs = event.tabletsCount
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(DueOrange, DueOrangeSoft)),
                RoundedCornerShape(28.dp)
            )
    ) {
        // Decorative pills + sparkle in the corner
        PillDecor(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 22.dp)
                .size(width = 34.dp, height = 20.dp)
        )
        SparkleDecor(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 46.dp, end = 16.dp)
                .size(16.dp),
            color = Color(0xCCFFFFFF)
        )
        Column(Modifier.padding(22.dp)) {
            Box(
                Modifier
                    .background(Color(0x33000000), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    if (state.nextDoseDue) "DUE NOW" else "NEXT DOSE",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                timeLabel,
                style = MaterialTheme.typography.headlineLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                partOfDay,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xEEFFFFFF)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "$meds ${if (meds == 1) "medicine" else "medicines"} • $tabs ${if (tabs == 1) "tablet" else "tablets"}",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xEEFFFFFF),
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Stay on track 💛",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xCCFFFFFF)
            )
            Spacer(Modifier.height(16.dp))
            if (event.allTaken) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CheckCircle, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Taken — nice work!", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onMarkTaken,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = DueOrange
                    )
                ) {
                    Icon(Icons.Filled.CheckCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Mark as taken", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Today's progress: "X/Y doses taken", dual event markers, circular % ring. */
@Composable
private fun TodayProgressCard(state: HomeUiState) {
    val fraction by animateFloatAsState(
        targetValue = if (state.eventsTotal == 0) 0f
        else state.eventsCompleted.toFloat() / state.eventsTotal,
        animationSpec = tween(700),
        label = "ring"
    )
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Today's progress",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${state.eventsCompleted}/${state.eventsTotal} doses taken",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DarkOnSurfaceMuted
                )
                Spacer(Modifier.height(14.dp))
                // Dual event markers (morning / night, etc.)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.events.forEach { ev ->
                        EventMarkerRow(ev)
                    }
                }
            }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                ProgressRing(fraction = fraction, modifier = Modifier.size(96.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Complete",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DarkOnSurfaceMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun EventMarkerRow(ev: DoseEvent) {
    val icon: @Composable () -> Unit = {
        if (ev.timeMinutes >= 17 * 60) MoonDecor(Modifier.size(18.dp))
        else SunDecor(Modifier.size(18.dp))
    }
    val label = when {
        ev.timeMinutes < 12 * 60 -> "Morning"
        ev.timeMinutes < 17 * 60 -> "Afternoon"
        else -> "Night"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        val (dotColor, statusText) = when {
            ev.allTaken -> StatusTaken to "Taken"
            System.currentTimeMillis() > ev.scheduledMillis + 60 * 60_000L -> StatusMissed to "Overdue"
            else -> DueOrange to "Due"
        }
        Box(
            Modifier
                .background(dotColor.copy(alpha = 0.18f), RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(statusText, style = MaterialTheme.typography.labelLarge, color = dotColor)
        }
    }
}

/** A schedule event card: icon, time, medicines•tablets, status pill. Tap to open detail. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ScheduleEventCard(
    event: DoseEvent,
    onClick: () -> Unit,
    onRequestRevert: () -> Unit
) {
    val now = System.currentTimeMillis()
    val (accent, statusText) = when {
        event.allTaken -> StatusTaken to "Taken"
        now > event.scheduledMillis + 60 * 60_000L -> StatusMissed to "Overdue"
        now >= event.scheduledMillis -> DueOrange to "Due"
        else -> AccentPurple to "Upcoming"
    }
    val clickMod = if (event.allTaken) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onRequestRevert)
    } else Modifier.combinedClickable(onClick = onClick, onLongClick = {})
    SectionCard(modifier = clickMod, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .background(accent.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (event.timeMinutes >= 17 * 60) MoonDecor(Modifier.size(24.dp))
                else SunDecor(Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    com.tbmedtrack.app.util.ScheduleUtil.formatTime(event.timeMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${event.medicinesCount} ${if (event.medicinesCount == 1) "medicine" else "medicines"} • " +
                        "${event.tabletsCount} ${if (event.tabletsCount == 1) "tablet" else "tablets"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DarkOnSurfaceMuted
                )
            }
            Box(
                Modifier
                    .background(accent.copy(alpha = 0.18f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(statusText, style = MaterialTheme.typography.labelLarge, color = accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun NotScheduledCard(info: NotScheduledInfo) {
    SectionCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                PillDecor(Modifier.size(width = 30.dp, height = 18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info.medicineName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Next: ${info.nextDateLabel} • ${info.nextTablets} ${if (info.nextTablets == 1) "tablet" else "tablets"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DarkOnSurfaceMuted
                )
            }
            Text("Not today", style = MaterialTheme.typography.labelLarge, color = DarkOnSurfaceMuted)
        }
    }
}

/** Motivational footer card. */
@Composable
private fun MotivationalCard(complete: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(AccentPurple.copy(alpha = 0.30f), Color(0x224F46E5))),
                RoundedCornerShape(24.dp)
            )
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp))
    ) {
        SparkleDecor(
            Modifier.align(Alignment.TopEnd).padding(14.dp).size(18.dp),
            color = Color(0xCCA5B4FC)
        )
        Column(Modifier.padding(20.dp)) {
            Text(
                if (complete) "You did it today! 🌟" else "Small steps make a big difference",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (complete) "Every dose taken is a win. Rest easy." else "You got this! 💪",
                style = MaterialTheme.typography.bodyMedium,
                color = DarkOnSurfaceMuted
            )
        }
    }
}

/**
 * Food Timing card: last food, medicine-available time, live countdown, and the "I HAVE EATEN"
 * button. When the food gap is complete it shows an "OPEN MEDICATION" affordance instead.
 */
@Composable
private fun FoodTimingCard(
    food: FoodUiState,
    onEaten: () -> Unit,
    onOpenMedication: () -> Unit
) {
    // Live 1-second ticker so the countdown updates on screen.
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(food.medicineAvailableMillis, food.lastFoodMillis) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val available = food.medicineAvailableMillis != null && now >= food.medicineAvailableMillis
    val complete = available && food.hasPendingGapDose

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🍽️", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(8.dp))
            Text(
                if (complete) "Food gap complete ✓" else "Food timing",
                style = MaterialTheme.typography.titleMedium,
                color = if (complete) StatusTaken else MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(10.dp))

        Text("Last food", style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
        Text(
            food.lastFoodMillis?.let { clockShort(it) } ?: "Not recorded",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        if (food.hasPendingGapDose && food.medicineAvailableMillis != null) {
            Spacer(Modifier.height(8.dp))
            Text("Medicine available", style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
            Text(
                if (available) "Available now" else clockShort(food.medicineAvailableMillis),
                style = MaterialTheme.typography.titleMedium,
                color = if (available) StatusTaken else DueOrange,
                fontWeight = FontWeight.SemiBold
            )
            if (!available) {
                Spacer(Modifier.height(4.dp))
                Text(
                    countdownLabel(food.medicineAvailableMillis - now) + " remaining",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (food.lastFoodMillis != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Delayed because you recorded food at ${clockShort(food.lastFoodMillis)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DarkOnSurfaceMuted
                    )
                }
            }
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                "Tap when you eat so medicine timing stays accurate.",
                style = MaterialTheme.typography.bodySmall,
                color = DarkOnSurfaceMuted
            )
        }

        Spacer(Modifier.height(14.dp))
        if (complete) {
            Button(
                onClick = onOpenMedication,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = StatusTaken, contentColor = Color.White)
            ) { Text("OPEN MEDICATION", fontWeight = FontWeight.Bold) }
        } else {
            Button(
                onClick = onEaten,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DueOrange, contentColor = Color.White)
            ) { Text("🍽️  I HAVE EATEN", fontWeight = FontWeight.Bold) }
        }
    }
}

private fun clockShort(millis: Long): String {
    val lt = java.time.Instant.ofEpochMilli(millis)
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return com.tbmedtrack.app.util.ScheduleUtil.formatTime(lt.hour * 60 + lt.minute)
}

private fun countdownLabel(remainingMillis: Long): String {
    val total = (remainingMillis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%dh %02dm %02ds", h, m, s)
    else String.format("%02dm %02ds", m, s)
}
