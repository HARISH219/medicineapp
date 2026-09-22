package com.tbmedtrack.app.ui.medicines

import android.app.Application
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.data.db.MedicationLog
import com.tbmedtrack.app.ui.components.EmptyState
import com.tbmedtrack.app.ui.components.statusVisual
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MedicineHistoryScreen(medicineId: Long) {
    val context = LocalContext.current
    val repo = ServiceLocator.medRepository(context)

    val logs by produceState(initialValue = emptyList<MedicationLog>(), medicineId) {
        value = repo.getLogsForMedicine(medicineId)
    }
    val name by produceState(initialValue = "", medicineId) {
        value = repo.getMedicine(medicineId)?.name ?: ""
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(name, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
        }
        if (logs.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.EventNote,
                    title = "No history yet",
                    subtitle = "Recorded doses for this medicine will appear here."
                )
            }
        } else {
            items(logs, key = { it.id }) { log -> LogRow(log) }
        }
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val timeFmt = DateTimeFormatter.ofPattern("hh:mm a")

@Composable
private fun LogRow(log: MedicationLog) {
    val visual = statusVisual(log.status)
    val date = LocalDate.ofEpochDay(log.scheduledEpochDay)
    val schedTime = Instant.ofEpochMilli(log.scheduledDateTime)
        .atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(visual.icon, visual.label, tint = visual.color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${date.format(dateFmt)} • $schedTime", style = MaterialTheme.typography.bodyLarge)
            val detail = when (log.status) {
                DoseStatus.TAKEN -> {
                    val t = log.actualTakenDateTime?.let {
                        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)
                    } ?: ""
                    "Taken${if (t.isNotEmpty()) " at $t" else ""}"
                }
                DoseStatus.MISSED -> "Missed"
                DoseStatus.SNOOZED -> "Snoozed (${log.snoozeCount}x)"
                DoseStatus.SKIPPED -> "Skipped"
                DoseStatus.REVERTED -> "Not recorded (reverted)"
                DoseStatus.SCHEDULED -> "Scheduled"
            }
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = visual.color)
        }
    }
}
