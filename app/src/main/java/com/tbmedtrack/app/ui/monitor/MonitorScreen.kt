package com.tbmedtrack.app.ui.monitor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.sync.SyncPhase
import com.tbmedtrack.app.ui.theme.AccentPurple
import com.tbmedtrack.app.ui.theme.DarkBackground
import com.tbmedtrack.app.ui.theme.DarkBackgroundTop
import com.tbmedtrack.app.ui.theme.DarkOnSurface
import com.tbmedtrack.app.ui.theme.DarkOnSurfaceMuted
import com.tbmedtrack.app.ui.theme.DarkOutline
import com.tbmedtrack.app.ui.theme.DarkSurface
import com.tbmedtrack.app.ui.theme.DarkSurfaceElevated
import com.tbmedtrack.app.ui.theme.Indigo
import com.tbmedtrack.app.ui.theme.StatusMissed
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.ui.theme.StatusUpcoming
import com.tbmedtrack.app.util.ScheduleUtil
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── palette helpers ───────────────────────────────────────────────────
private val CardBg = DarkSurface
private val CardElevated = DarkSurfaceElevated
private val Muted = DarkOnSurfaceMuted
private val OnBg = DarkOnSurface

private fun statusColor(s: MonitorStatus): Color = when (s) {
    MonitorStatus.TAKEN -> StatusTaken
    MonitorStatus.UPCOMING -> StatusUpcoming
    MonitorStatus.NOT_TAKEN -> StatusMissed
}
private fun statusText(s: MonitorStatus): String = when (s) {
    MonitorStatus.TAKEN -> "TAKEN"
    MonitorStatus.UPCOMING -> "UPCOMING"
    MonitorStatus.NOT_TAKEN -> "NOT TAKEN"
}
private fun dayColor(s: DayStatus): Color = when (s) {
    DayStatus.COMPLETED -> StatusTaken
    DayStatus.UPCOMING -> StatusUpcoming
    DayStatus.MISSED -> StatusMissed
    DayStatus.NONE -> Muted
}

private val timeFmt = DateTimeFormatter.ofPattern("hh:mm a")
private val dateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy")
private val dayHeaderFmt = DateTimeFormatter.ofPattern("EEEE, d MMM")
private fun clock(ms: Long): String = runCatching {
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)
}.getOrDefault("—")

private enum class MonitorTab(val label: String) { TODAY("Today"), SCHEDULE("Schedule"), HISTORY("History"), FOOD("Food") }
private enum class BottomDest(val label: String, val icon: String) {
    HOME("Home", "🏠"), SCHEDULE("Schedule", "🗓"), STATS("Stats", "📊"), HISTORY("History", "🕘"), SETTINGS("Settings", "⚙️")
}

@Composable
fun MonitorScreen(vm: MonitorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var bottom by remember { mutableStateOf(BottomDest.HOME) }
    var tab by remember { mutableStateOf(MonitorTab.TODAY) }
    var selectedDay by remember { mutableStateOf(ScheduleUtil.today().toEpochDay()) }
    var visibleMonth by remember { mutableStateOf(YearMonth.now()) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(DarkBackgroundTop, DarkBackground)))
    ) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 96.dp)
        ) {
            item { Header(state, onRefresh = vm::refresh) }

            when (bottom) {
                BottomDest.SETTINGS -> settingsContent(state, vm)
                BottomDest.STATS -> statsContent(state)
                else -> {
                    // Home / Schedule / History bottom items reuse the rich dashboard, jumping to
                    // the relevant tab so navigation always lands somewhere useful.
                    item { CloudSyncCard(state, onCheck = vm::checkSync) }
                    state.errorMessage?.let { msg -> item { ErrorNote(msg) } }
                    item { TodaySummaryCard(state) }
                    state.next?.let { item { NextMedicineCard(it, state.nowMillis) } }
                    item {
                        CalendarCard(
                            state = state,
                            month = visibleMonth,
                            selectedDay = selectedDay,
                            onMonth = { visibleMonth = it },
                            onSelect = { day -> selectedDay = day; tab = MonitorTab.SCHEDULE },
                            onToday = {
                                visibleMonth = YearMonth.now()
                                selectedDay = ScheduleUtil.today().toEpochDay()
                            }
                        )
                    }
                    item { TabRow(tab, onTab = { tab = it }) }
                    tabContent(state, tab, selectedDay)
                }
            }
        }

        BottomNav(
            current = bottom,
            onSelect = {
                bottom = it
                tab = when (it) {
                    BottomDest.SCHEDULE -> MonitorTab.SCHEDULE
                    BottomDest.HISTORY -> MonitorTab.HISTORY
                    else -> MonitorTab.TODAY
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ── Header ─────────────────────────────────────────────────────────────
@Composable
private fun Header(state: MonitorUiState, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(Indigo, AccentPurple))),
            contentAlignment = Alignment.Center
        ) { Text("✚", color = Color.White, fontSize = 17.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("MedTrack", color = OnBg, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Monitoring Device", color = Muted, fontSize = 12.sp)
        }
        // Last synced chip
        Row(
            Modifier.clip(RoundedCornerShape(10.dp)).background(CardBg)
                .border(1.dp, DarkOutline, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("☁", color = Indigo, fontSize = 13.sp)
            Spacer(Modifier.width(6.dp))
            Column {
                Text("Last synced", color = Muted, fontSize = 9.sp)
                Text(if (state.sync.lastDownloadAt > 0) clock(state.sync.lastDownloadAt) else "—",
                    color = OnBg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.width(8.dp))
        RefreshButton(spinning = state.sync.phase == SyncPhase.SYNCING || state.checkingSync, onClick = onRefresh)
    }
}

@Composable
private fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val angle by animateFloatAsState(if (spinning) 360f else 0f, tween(700), label = "spin")
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(CardElevated)
            .border(1.dp, DarkOutline, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text("↻", color = Indigo, fontSize = 17.sp, modifier = Modifier.rotate(angle)) }
}

// ── Cloud sync card ─────────────────────────────────────────────────────
@Composable
private fun CloudSyncCard(state: MonitorUiState, onCheck: () -> Unit) {
    val phase = state.sync.phase
    val (dotC, label) = when {
        !state.sync.configured -> Muted to "Local only"
        phase == SyncPhase.SYNCING -> StatusUpcoming to "Syncing…"
        phase == SyncPhase.OFFLINE -> Muted to "Offline"
        phase == SyncPhase.ERROR -> StatusMissed to "Sync error"
        else -> StatusTaken to "Up to date"
    }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("☁", color = Indigo, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text("CLOUD SYNC", color = OnBg, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                modifier = Modifier.weight(1f))
            StatusPill(dotC, label)
        }
        Spacer(Modifier.height(10.dp))
        InfoLine("Last downloaded", if (state.sync.lastDownloadAt > 0) clock(state.sync.lastDownloadAt) else "—")
        InfoLine("Cloud version", "#${state.sync.cloudVersion}")
        InfoLine("Next automatic check", state.nextCheckLabel.ifBlank { "—" })
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AccentButton(if (state.checkingSync) "CHECKING…" else "CHECK SYNC", enabled = !state.checkingSync, onClick = onCheck)
            Spacer(Modifier.width(10.dp))
            Text(
                if (phase == SyncPhase.SYNCED || !state.sync.configured) "Auto sync is always on"
                else "Cloud sync is always active",
                color = Muted, fontSize = 11.sp
            )
        }
        state.lastCheck?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.overallLabel, color = if (it.overallOk) StatusTaken else StatusUpcoming, fontSize = 12.sp)
        }
    }
}

// ── Today summary + circular progress ───────────────────────────────────
@Composable
private fun TodaySummaryCard(state: MonitorUiState) {
    val groups = state.todayGroups
    val meds = groups.flatMap { it.medicines }
    val taken = meds.count { it.status == MonitorStatus.TAKEN }
    val total = meds.size
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Today", color = OnBg, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(ScheduleUtil.today().format(dateFmt), color = Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${groups.size} ${if (groups.size == 1) "Dose" else "Doses"}", color = OnBg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            ProgressRing(taken, total)
        }
        if (groups.isEmpty() && !state.loading) {
            Spacer(Modifier.height(10.dp))
            Text("No doses scheduled for today were downloaded.", color = Muted, fontSize = 12.sp)
        }
        groups.forEach { g ->
            Spacer(Modifier.height(10.dp))
            DoseSummaryRow(g)
        }
    }
}

@Composable
private fun ProgressRing(taken: Int, total: Int) {
    val target = if (total > 0) taken.toFloat() / total else 0f
    val progress by animateFloatAsState(target, tween(500), label = "ring")
    val ringColor = if (total > 0 && taken == total) StatusTaken else StatusUpcoming
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val sw = 6.dp.toPx()
            drawArc(color = DarkOutline, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = Stroke(width = sw, cap = StrokeCap.Round),
                topLeft = Offset(sw / 2, sw / 2),
                size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw))
            drawArc(color = ringColor, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                style = Stroke(width = sw, cap = StrokeCap.Round),
                topLeft = Offset(sw / 2, sw / 2),
                size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$taken/$total", color = OnBg, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text("Taken", color = Muted, fontSize = 8.sp)
        }
    }
}

@Composable
private fun DoseSummaryRow(g: MonitoredDoseGroup) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardElevated).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(g.emoji, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(g.label, color = OnBg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(ScheduleUtil.formatTime(g.timeMinutes), color = Muted, fontSize = 12.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            StatusPill(statusColor(g.status), statusText(g.status))
            Spacer(Modifier.height(3.dp))
            Text(
                if (g.status == MonitorStatus.TAKEN && g.takenAtMillis != null) "Taken at ${clock(g.takenAtMillis!!)}"
                else g.tabletsText,
                color = Muted, fontSize = 10.sp
            )
        }
    }
}

// ── Next medicine card ──────────────────────────────────────────────────
@Composable
private fun NextMedicineCard(next: NextMedicine, now: Long) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⏰", fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Text("NEXT MEDICINE", color = OnBg, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Indigo.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                Text(countdown(next.scheduledMillis, now), color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(next.name, color = OnBg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text("${ScheduleUtil.formatTime(next.timeMinutes)}  •  ${next.doseText}", color = Muted, fontSize = 12.sp)
            }
            StatusPill(statusColor(next.status), statusText(next.status))
        }
    }
}

private fun countdown(target: Long, now: Long): String {
    val diff = target - now
    if (diff <= 0) return "Due now"
    val mins = diff / 60_000L
    val h = mins / 60
    val m = mins % 60
    return if (h > 0) "In ${h}h ${m}m" else "In ${m}m"
}

// ── Calendar ────────────────────────────────────────────────────────────
@Composable
private fun CalendarCard(
    state: MonitorUiState,
    month: YearMonth,
    selectedDay: Long,
    onMonth: (YearMonth) -> Unit,
    onSelect: (Long) -> Unit,
    onToday: () -> Unit
) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), color = OnBg, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            NavArrow("‹") { onMonth(month.minusMonths(1)) }
            Spacer(Modifier.width(6.dp))
            NavArrow("›") { onMonth(month.plusMonths(1)) }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Indigo.copy(alpha = 0.18f)).clickable(onClick = onToday).padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("Today", color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach {
                Text(it, color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        val firstOfMonth = month.atDay(1)
        // Column 0 = Sunday.
        val leadBlanks = firstOfMonth.dayOfWeek.value % 7
        val daysInMonth = month.lengthOfMonth()
        val cells = leadBlanks + daysInMonth
        val rows = (cells + 6) / 7
        val todayEpoch = ScheduleUtil.today().toEpochDay()
        var dayNum = 1
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val idx = r * 7 + c
                    if (idx < leadBlanks || dayNum > daysInMonth) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = month.atDay(dayNum)
                        val epoch = date.toEpochDay()
                        val status = state.dayStatus[epoch] ?: DayStatus.NONE
                        CalendarDay(
                            day = dayNum,
                            status = status,
                            selected = epoch == selectedDay,
                            isToday = epoch == todayEpoch,
                            onClick = { onSelect(epoch) },
                            modifier = Modifier.weight(1f)
                        )
                        dayNum++
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Legend(StatusTaken, "Completed"); Legend(StatusUpcoming, "Upcoming"); Legend(StatusMissed, "Missed"); Legend(Muted, "No data")
        }
    }
}

@Composable
private fun CalendarDay(day: Int, status: DayStatus, selected: Boolean, isToday: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) Indigo.copy(alpha = 0.25f) else Color.Transparent, label = "daybg")
    Box(modifier.aspectRatio(1f).padding(2.dp)) {
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(bg)
                .then(if (selected) Modifier.border(1.5.dp, AccentPurple, RoundedCornerShape(10.dp)) else Modifier)
                .clickable(onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("$day", color = if (isToday) AccentPurple else OnBg,
                fontSize = 13.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (status == DayStatus.NONE) DarkOutline else dayColor(status)))
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Muted, fontSize = 9.sp)
    }
}

@Composable
private fun NavArrow(sym: String, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(CardElevated).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(sym, color = OnBg, fontSize = 16.sp)
    }
}

// ── Tabs ────────────────────────────────────────────────────────────────
@Composable
private fun TabRow(selected: MonitorTab, onTab: (MonitorTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        MonitorTab.entries.forEach { t ->
            val active = t == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (active) Indigo else Color.Transparent)
                    .clickable { onTab(t) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { Text(t.label, color = if (active) Color.White else Muted, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.tabContent(
    state: MonitorUiState, tab: MonitorTab, selectedDay: Long
) {
    when (tab) {
        MonitorTab.TODAY -> {
            val groups = state.groupsForDay(selectedDay)
            val header = LocalDate.ofEpochDay(selectedDay).format(dayHeaderFmt)
            item { SubHeader(if (selectedDay == state.todayEpochDay) "Today · $header" else header) }
            if (groups.isEmpty()) item { EmptyNote("No medication downloaded for this day.") }
            items(groups, key = { "t-${it.epochDay}-${it.timeMinutes}" }) { g -> ExpandableDoseCard(g) }
        }
        MonitorTab.SCHEDULE -> {
            val today = state.todayEpochDay
            val upcoming = state.groups.filter { it.epochDay >= today }.sortedWith(compareBy({ it.epochDay }, { it.timeMinutes }))
            if (upcoming.isEmpty()) item { EmptyNote("No upcoming schedule downloaded.") }
            var lastDay = Long.MIN_VALUE
            upcoming.forEach { g ->
                if (g.epochDay != lastDay) {
                    lastDay = g.epochDay
                    val label = when (g.epochDay) {
                        today -> "Today"
                        today + 1 -> "Tomorrow"
                        else -> LocalDate.ofEpochDay(g.epochDay).format(dayHeaderFmt)
                    }
                    item { SubHeader(label) }
                }
                item(key = "s-${g.epochDay}-${g.timeMinutes}") { ScheduleRow(g) }
            }
        }
        MonitorTab.HISTORY -> {
            val today = state.todayEpochDay
            val past = state.groups.filter { it.epochDay <= today }
                .sortedWith(compareByDescending<MonitoredDoseGroup> { it.epochDay }.thenByDescending { it.timeMinutes })
            if (past.isEmpty()) item { EmptyNote("No history downloaded yet.") }
            var lastDay = Long.MAX_VALUE
            past.forEach { g ->
                if (g.epochDay != lastDay) {
                    lastDay = g.epochDay
                    val label = when (g.epochDay) {
                        today -> "Today · ${LocalDate.ofEpochDay(g.epochDay).format(dayHeaderFmt)}"
                        today - 1 -> "Yesterday · ${LocalDate.ofEpochDay(g.epochDay).format(dayHeaderFmt)}"
                        else -> LocalDate.ofEpochDay(g.epochDay).format(dayHeaderFmt)
                    }
                    item { SubHeader(label) }
                }
                item(key = "h-${g.epochDay}-${g.timeMinutes}") { ExpandableDoseCard(g) }
            }
        }
        MonitorTab.FOOD -> {
            item { SubHeader("Food history") }
            if (state.foods.isEmpty()) item { EmptyNote("No food event has been downloaded yet.") }
            items(state.foods, key = { "f-${it.uuid}" }) { f -> FoodCard(f) }
        }
    }
}

// ── Expandable dose card ────────────────────────────────────────────────
@Composable
private fun ExpandableDoseCard(g: MonitoredDoseGroup) {
    var expanded by remember { mutableStateOf(false) }
    Card {
        Row(Modifier.clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Text(g.emoji, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(g.label, color = OnBg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(ScheduleUtil.formatTime(g.timeMinutes), color = Muted, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                StatusPill(statusColor(g.status), statusText(g.status))
                if (g.status == MonitorStatus.TAKEN && g.takenAtMillis != null)
                    Text("Taken at ${clock(g.takenAtMillis!!)}", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(if (expanded) "⌃" else "⌄", color = Muted, fontSize = 16.sp)
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(8.dp))
                g.medicines.forEach { m -> MedicineRow(m) }
            }
        }
    }
}

@Composable
private fun MedicineRow(m: MonitoredMedicine) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(statusColor(m.status)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(m.name, color = OnBg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(m.doseText, color = Muted, fontSize = 11.sp)
        }
        if (m.takenAtMillis != null) {
            Text(clock(m.takenAtMillis), color = Muted, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
        }
        StatusPill(statusColor(m.status), statusText(m.status), small = true)
    }
}

@Composable
private fun ScheduleRow(g: MonitoredDoseGroup) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(CardBg).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(78.dp)) {
            Text(ScheduleUtil.formatTime(g.timeMinutes), color = OnBg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text("${g.emoji} ${g.label}", color = OnBg, fontSize = 13.sp)
            Text(g.tabletsText, color = Muted, fontSize = 11.sp)
        }
        StatusPill(statusColor(g.status), statusText(g.status))
    }
}

// ── Food card ───────────────────────────────────────────────────────────
@Composable
private fun FoodCard(f: MonitoredFood) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🍽️", fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Food taken", color = OnBg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(LocalDate.ofEpochDay(ScheduleUtil.localDateTime(f.foodTimeMillis).toLocalDate().toEpochDay()).format(dayHeaderFmt),
                    color = Muted, fontSize = 11.sp)
            }
            Text(clock(f.foodTimeMillis), color = OnBg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        if (f.gapMinutes > 0 && f.eligibleMillis != null) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CardElevated).padding(10.dp)) {
                InfoLine("Food taken", clock(f.foodTimeMillis))
                InfoLine("Medicine interval", "${f.gapMinutes / 60}h ${f.gapMinutes % 60}m")
                InfoLine("Medicine eligible", clock(f.eligibleMillis!!))
            }
        }
    }
}

// ── Settings + Stats tabs ────────────────────────────────────────────────
private fun androidx.compose.foundation.lazy.LazyListScope.settingsContent(state: MonitorUiState, vm: MonitorViewModel) {
    item { SubHeader("Monitoring Device Settings") }
    item {
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Monitoring Device", color = OnBg, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                StatusPill(if (state.sync.configured) StatusTaken else Muted, if (state.sync.configured) "Connected" else "Local only")
            }
            Spacer(Modifier.height(10.dp))
            InfoLine("Device", android.os.Build.MODEL ?: "This device")
            InfoLine("Role", "MONITORING")
            InfoLine("Cloud sync", "Always active")
            InfoLine("Last sync", if (state.sync.lastDownloadAt > 0) clock(state.sync.lastDownloadAt) else "—")
            InfoLine("Cloud version", "#${state.sync.cloudVersion}")
            InfoLine("Next automatic check", state.nextCheckLabel.ifBlank { "—" })
            Spacer(Modifier.height(10.dp))
            AccentButton(if (state.checkingSync) "CHECKING…" else "CHECK SYNC", enabled = !state.checkingSync, onClick = vm::checkSync)
        }
    }
    item {
        Card {
            Text("Read-only device", color = OnBg, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "This device only displays medication and food information synchronized from the primary device. It cannot add, edit, delete, or mark medication or food.",
                color = Muted, fontSize = 12.sp
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.statsContent(state: MonitorUiState) {
    item { SubHeader("Overview") }
    item {
        val today = state.todayGroups.flatMap { it.medicines }
        val taken = today.count { it.status == MonitorStatus.TAKEN }
        Card {
            InfoLine("Doses today", "${state.todayGroups.size}")
            InfoLine("Medicines taken today", "$taken / ${today.size}")
            InfoLine("Days with data", "${state.dayStatus.size}")
            InfoLine("Food records", "${state.foods.size}")
        }
    }
}

// ── Bottom navigation ─────────────────────────────────────────────────────
@Composable
private fun BottomNav(current: BottomDest, onSelect: (BottomDest) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(CardBg.copy(alpha = 0.98f))
            .navigationBarsPadding()
            .padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        BottomDest.entries.forEach { d ->
            val active = d == current
            Column(
                Modifier.clip(RoundedCornerShape(10.dp)).clickable { onSelect(d) }.padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(d.icon, fontSize = 17.sp, color = if (active) Indigo else Muted)
                Text(d.label, color = if (active) Indigo else Muted, fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ── Shared small components ────────────────────────────────────────────────
@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CardBg)
            .border(1.dp, DarkOutline, RoundedCornerShape(16.dp)).padding(16.dp),
        content = content
    )
}

@Composable
private fun StatusPill(dot: Color, label: String, small: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(dot.copy(alpha = 0.16f))
            .padding(horizontal = if (small) 7.dp else 9.dp, vertical = if (small) 3.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(if (small) 6.dp else 7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(5.dp))
        Text(label, color = dot, fontSize = if (small) 9.sp else 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = OnBg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AccentButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (enabled) Indigo else Indigo.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun SubHeader(text: String) {
    Text(text.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp))
}

@Composable
private fun EmptyNote(text: String) {
    Card { Text(text, color = Muted, fontSize = 12.sp) }
}

@Composable
private fun ErrorNote(text: String) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(StatusUpcoming.copy(alpha = 0.12f)).padding(12.dp)
    ) {
        Text("Sync temporarily unavailable", color = StatusUpcoming, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(text, color = Muted, fontSize = 11.sp)
        Text("The last downloaded information remains visible.", color = Muted, fontSize = 11.sp)
    }
}
