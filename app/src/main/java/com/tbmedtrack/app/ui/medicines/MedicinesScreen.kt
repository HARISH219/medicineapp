package com.tbmedtrack.app.ui.medicines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.db.Frequency
import com.tbmedtrack.app.data.db.MedicineWithSchedules
import com.tbmedtrack.app.ui.components.EmptyState
import com.tbmedtrack.app.ui.theme.StatusNeutral
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.util.ScheduleUtil

@Composable
fun MedicinesScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onHistory: (Long) -> Unit,
    vm: MedicinesViewModel = viewModel()
) {
    val meds by vm.medicines.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<MedicineWithSchedules?>(null) }

    if (meds.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Medication,
            title = "No medicines added yet",
            subtitle = "Add the medicines your doctor prescribed. You control every name, dose and time.",
            actionLabel = "Add medicine",
            onAction = onAdd
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        val tb = meds.filter { it.medicine.partOfTbRegimen }
        val other = meds.filter { !it.medicine.partOfTbRegimen }
        if (tb.isNotEmpty()) {
            item {
                Text("My TB Treatment", style = MaterialTheme.typography.titleLarge)
            }
            items(tb, key = { it.medicine.id }) { m ->
                MedicineCard(m, onEdit, onHistory,
                    onToggleActive = { vm.setActive(m.medicine.id, !m.medicine.active) },
                    onDelete = { deleteTarget = m })
            }
        }
        if (other.isNotEmpty()) {
            item {
                Text("Other medicines", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp))
            }
            items(other, key = { it.medicine.id }) { m ->
                MedicineCard(m, onEdit, onHistory,
                    onToggleActive = { vm.setActive(m.medicine.id, !m.medicine.active) },
                    onDelete = { deleteTarget = m })
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete medicine?") },
            text = {
                Text("Delete \"${target.medicine.name}\" and its future reminders? " +
                    "Past history for this medicine will remain in your records.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target.medicine.id)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MedicineCard(
    m: MedicineWithSchedules,
    onEdit: (Long) -> Unit,
    onHistory: (Long) -> Unit,
    onToggleActive: () -> Unit,
    onDelete: () -> Unit
) {
    val med = m.medicine
    val times = m.schedules.sortedBy { it.timeMinutes }
        .joinToString(" • ") { ScheduleUtil.formatTime(it.timeMinutes) }
    val freqLabel = m.schedules.firstOrNull()?.let { frequencyLabel(it.frequency, it.daysOfWeek, it.intervalDays) } ?: ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Text("💊") }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(med.name, style = MaterialTheme.typography.titleMedium)
                    Text("Dose: ${med.dose} ${med.unit}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ActivePill(med.active)
            }
            if (times.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(times, style = MaterialTheme.typography.bodyMedium)
                Text(freqLabel, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = { onEdit(med.id) }) {
                    Icon(Icons.Filled.Edit, "Edit ${med.name}")
                }
                IconButton(onClick = onToggleActive) {
                    if (med.active) Icon(Icons.Filled.Pause, "Pause ${med.name}")
                    else Icon(Icons.Filled.PlayArrow, "Resume ${med.name}")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onHistory(med.id) }) { Text("History") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, "Delete ${med.name}", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ActivePill(active: Boolean) {
    val color = if (active) StatusTaken else StatusNeutral
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            if (active) "Active" else "Paused",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

fun frequencyLabel(freq: Frequency, daysOfWeek: String, interval: Int): String = when (freq) {
    Frequency.EVERY_DAY -> "Every day"
    Frequency.SPECIFIC_DAYS -> {
        val days = daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }
            .sorted().joinToString(", ") { ScheduleUtil.dayOfWeekLabel(it) }
        if (days.isBlank()) "Specific days" else days
    }
    Frequency.EVERY_X_DAYS -> if (interval == 1) "Every day" else "Every $interval days"
}
