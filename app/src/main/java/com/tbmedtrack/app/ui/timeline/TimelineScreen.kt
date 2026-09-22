package com.tbmedtrack.app.ui.timeline

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.model.DayAdherence
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.theme.StatusMissed
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.ui.theme.StatusUpcoming
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private data class TimelineDay(
    val dayNumber: Int,
    val date: LocalDate,
    val adherence: DayAdherence,
    val recorded: Boolean,
    val historical: Boolean
)

/**
 * Day-by-day treatment timeline. The treatment-day number is computed from the configured
 * treatment start date, never from installs or syncs. Historical (imported) days show as
 * recorded and are never displayed as missed.
 */
@Composable
fun TimelineScreen() {
    val context = LocalContext.current
    val repo = ServiceLocator.medRepository(context)
    val settings = ServiceLocator.settingsRepository(context)
    val fmt = DateTimeFormatter.ofPattern("EEE, MMM d")

    val data by produceState(initialValue = Pair<LocalDate?, List<TimelineDay>>(null, emptyList())) {
        val s = settings.settings.first()
        val startDay = if (s.treatmentStartDay > 0) s.treatmentStartDay
        else repo.getAllMedicinesWithSchedules().minOfOrNull { it.medicine.startDate } ?: ScheduleUtil.today().toEpochDay()
        val startDate = LocalDate.ofEpochDay(startDay)
        val today = ScheduleUtil.today()

        val list = mutableListOf<TimelineDay>()
        var d = startDate
        var n = 1
        while (!d.isAfter(today)) {
            val summary = repo.getDaySummary(d)
            val doses = repo.getDosesForDay(d)
            val historical = doses.any { it.historical }
            val recorded = summary.scheduled > 0 && summary.taken == summary.scheduled
            list += TimelineDay(n, d, summary.adherence, recorded, historical)
            d = d.plusDays(1); n++
        }
        value = Pair(startDate, list.reversed()) // most recent first
    }

    val startDate = data.first
    val days = data.second

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item {
            SectionCard {
                Text("TB TREATMENT", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                startDate?.let {
                    Text("Started ${it.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))}",
                        style = MaterialTheme.typography.titleMedium)
                }
                Text("Day ${days.firstOrNull()?.dayNumber ?: 0}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        items(days) { td -> TimelineRow(td, fmt) }
    }
}

@Composable
private fun TimelineRow(td: TimelineDay, fmt: DateTimeFormatter) {
    val (icon, label, color) = when {
        td.historical && td.recorded -> Triple("✓", "Recorded (history)", StatusTaken)
        td.recorded -> Triple("✓", "Medication recorded", StatusTaken)
        td.adherence == DayAdherence.MISSED -> Triple("✕", "Not fully recorded", StatusMissed)
        td.adherence == DayAdherence.PARTIAL -> Triple("!", "Partly recorded", StatusUpcoming)
        else -> Triple("○", "No record", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Day ${td.dayNumber}", style = MaterialTheme.typography.titleMedium)
                Text(td.date.format(fmt), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$icon $label", color = color, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}
