package com.tbmedtrack.app.ui.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.data.model.DoseEvent
import com.tbmedtrack.app.data.model.ScheduledDose
import com.tbmedtrack.app.ui.components.statusVisual
import com.tbmedtrack.app.util.ScheduleUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DoseEventCard(
    event: DoseEvent,
    onMarkAllTaken: () -> Unit,
    onMarkDoseTaken: (ScheduledDose) -> Unit,
    modifier: Modifier = Modifier
) {
    val now = System.currentTimeMillis()
    val due = now >= event.scheduledMillis && now <= event.scheduledMillis + 60 * 60_000L &&
        event.doses.any { it.status == DoseStatus.SCHEDULED }

    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ScheduleUtil.formatTime(event.timeMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${event.takenCount}/${event.totalCount} taken",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(10.dp))

            event.doses.forEach { dose ->
                DoseRow(dose, due, onMarkDoseTaken)
                Spacer(Modifier.height(8.dp))
            }

            if (event.doses.any { it.status == DoseStatus.SCHEDULED || it.status == DoseStatus.SNOOZED }) {
                Spacer(Modifier.height(4.dp))
                if (event.totalCount > 1) {
                    Button(onClick = onMarkAllTaken, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Done, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Mark all as taken")
                    }
                } else {
                    val single = event.doses.first()
                    Button(onClick = { onMarkDoseTaken(single) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Done, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Mark as taken")
                    }
                }
            }
        }
    }
}

@Composable
private fun DoseRow(
    dose: ScheduledDose,
    due: Boolean,
    onMarkTaken: (ScheduledDose) -> Unit
) {
    val visual = statusVisual(dose.status, due)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(visual.container),
            contentAlignment = Alignment.Center
        ) {
            Text("💊")
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(dose.medicineName, style = MaterialTheme.typography.titleMedium)
            // Exact tablet count is the primary info for phased meds; dose text for others.
            Text(
                if (dose.tabletsScheduled > 0)
                    "${dose.tabletsScheduled} ${if (dose.tabletsScheduled == 1) "TABLET" else "TABLETS"}"
                else dose.doseText + (if (dose.foodTiming != "Doesn't matter") " • ${dose.foodTiming}" else ""),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            if (dose.phaseName.isNotBlank()) {
                val dayLabel = if (dose.phaseDayCount != null)
                    " • Day ${dose.treatmentDay} / ${dose.phaseDayCount}"
                else " • Day ${dose.treatmentDay}"
                Text(
                    dose.phaseName + dayLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusLabel(dose, visual.label, visual.color, visual.icon)
        }
    }
}

@Composable
private fun StatusLabel(
    dose: ScheduledDose,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        val text = when (dose.status) {
            DoseStatus.TAKEN -> {
                val t = dose.takenAtMillis?.let { formatClock(it) } ?: ""
                val delay = dose.takenAtMillis?.let { (it - dose.scheduledMillis) / 60000L } ?: 0L
                buildString {
                    append("Taken")
                    if (t.isNotEmpty()) append(" at $t")
                    if (delay in 1..600) append(" • $delay min late")
                }
            }
            DoseStatus.MISSED -> "MISSED — no dose recorded"
            DoseStatus.SNOOZED -> "Snoozed"
            else -> label
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

private fun formatClock(millis: Long): String {
    val fmt = DateTimeFormatter.ofPattern("hh:mm a")
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(fmt)
}
