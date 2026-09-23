package com.tbmedtrack.app.ui.phase

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.ScheduleEngine
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.theme.StatusNeutral
import com.tbmedtrack.app.ui.theme.StatusTaken
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private data class PreviewRow(val label: String, val tabletsText: String, val scheduled: Boolean)

/**
 * Confirmation / preview of a phased prescription: shows the computed first 14 days and the
 * subsequent weekly schedule so the user can verify the calculated dosing before relying on it.
 * This is a read-only preview of the *configured* prescription — not a medical recommendation.
 */
@Composable
fun PhasePreviewScreen(medicineId: Long) {
    val context = LocalContext.current
    val fmt = DateTimeFormatter.ofPattern("EEE, MMM d")

    val data by produceState(
        initialValue = Triple<String, List<PreviewRow>, List<PreviewRow>>("", emptyList(), emptyList()),
        medicineId
    ) {
        val repo = ServiceLocator.medRepository(context)
        val med = repo.getMedicine(medicineId) ?: return@produceState
        val phases = repo.phasesFor(medicineId)
        if (phases.isEmpty()) { value = Triple(med.name, emptyList(), emptyList()); return@produceState }
        val start = LocalDate.ofEpochDay(med.startDate)

        // First 14 treatment days.
        val first14 = (0 until 14).map { offset ->
            val d = start.plusDays(offset.toLong())
            val r = ScheduleEngine.evaluate(med, phases, d)
            PreviewRow(
                "Day ${offset + 1} · ${d.format(fmt)}",
                if (r.scheduled) "${r.tablets} ${if (r.tablets == 1) "tablet" else "tablets"}" else "—",
                r.scheduled
            )
        }
        // Following 2 weeks (to show the weekly pattern) from day 15.
        val weekly = (14 until 28).map { offset ->
            val d = start.plusDays(offset.toLong())
            val r = ScheduleEngine.evaluate(med, phases, d)
            PreviewRow(
                d.format(fmt),
                if (r.scheduled) "${r.tablets} ${if (r.tablets == 1) "tablet" else "tablets"}" else "Not scheduled",
                r.scheduled
            )
        }
        value = Triple(med.name, first14, weekly)
    }

    val (name, first14, weekly) = data

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item {
            Text(name, style = MaterialTheme.typography.headlineMedium)
            Text("This is your configured schedule. Please verify it matches your prescription.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (first14.isNotEmpty()) {
            item { Text("Phase 1 — first 14 days", style = MaterialTheme.typography.titleLarge) }
            items(first14) { r -> PreviewRowView(r) }
        }
        if (weekly.isNotEmpty()) {
            item {
                Spacer(Modifier.height(4.dp))
                Text("Phase 2 — weekly schedule", style = MaterialTheme.typography.titleLarge)
            }
            items(weekly) { r -> PreviewRowView(r) }
        }
    }
}

@Composable
private fun PreviewRowView(r: PreviewRow) {
    SectionCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(r.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                r.tabletsText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (r.scheduled) StatusTaken else StatusNeutral
            )
        }
    }
}
