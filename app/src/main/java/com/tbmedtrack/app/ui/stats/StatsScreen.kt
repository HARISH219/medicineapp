package com.tbmedtrack.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.model.Statistics
import com.tbmedtrack.app.ui.components.EmptyState
import com.tbmedtrack.app.ui.components.SectionCard

@Composable
fun StatsScreen(vm: StatsViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    if (!state.hasData && !state.loading) {
        EmptyState(
            icon = Icons.Outlined.Insights,
            title = "No statistics yet",
            subtitle = "Start tracking your medication to see adherence, streaks and history."
        )
        return
    }

    val stats = state.monthStats ?: return

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item { AdherenceCard(state.monthLabel, stats) }
        item { StreakCard(stats) }
        item { WeeklyChartCard(state.weeklyBars) }
        item { TotalsGrid(stats, state) }
    }
}

@Composable
private fun AdherenceCard(monthLabel: String, stats: Statistics) {
    SectionCard {
        Text(monthLabel.uppercase(), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatPercent(stats.adherencePercent)}%",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text("Adherence this month", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            StatChip("✓ ${stats.taken}", "Taken")
            StatChip("✕ ${stats.missed}", "Missed")
            StatChip("⏰ ${stats.late}", "Late")
            StatChip("${stats.scheduled}", "Scheduled")
        }
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StreakCard(stats: Statistics) {
    SectionCard {
        Text("🔥 ${stats.currentStreak} day streak", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            if (stats.currentStreak > 0)
                "You've completed your medication schedule for ${stats.currentStreak} consecutive ${if (stats.currentStreak == 1) "day" else "days"}."
            else "Complete all of today's scheduled doses to start a streak.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text("Best streak: ${stats.bestStreak} days", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WeeklyChartCard(bars: List<Float>) {
    val labels = listOf("6d", "5d", "4d", "3d", "2d", "1d", "Now")
    SectionCard {
        Text("Last 7 days", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            bars.forEachIndexed { i, frac ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxHeight(fraction = frac.coerceIn(0.03f, 1f))
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (frac >= 0.999f) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.primary
                            )
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { i, l ->
                Text(l, Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TotalsGrid(stats: Statistics, state: StatsUiState) {
    SectionCard {
        Text("Totals", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        TotalRow("Today's completion", "${state.todayTaken}/${state.todayTotal}")
        TotalRow("Weekly adherence", "${formatPercent(state.weekAdherence)}%")
        TotalRow("Monthly adherence", "${formatPercent(state.monthAdherence)}%")
        TotalRow("Days tracked", "${stats.daysTracked}")
        TotalRow("Completed dose events", "${stats.completedDoseEvents}")
        TotalRow("Total doses taken", "${stats.totalTaken}")
        TotalRow("Total missed doses", "${stats.totalMissed}")
        TotalRow("Average delay", "${stats.averageDelayMinutes} min")
    }
}

@Composable
private fun TotalRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatPercent(p: Double): String =
    if (p == p.toLong().toDouble()) p.toLong().toString() else String.format("%.1f", p)
