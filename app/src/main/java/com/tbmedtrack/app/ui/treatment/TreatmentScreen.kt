package com.tbmedtrack.app.ui.treatment

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.db.MedicineWithSchedules
import com.tbmedtrack.app.ui.components.EmptyState
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.medicines.MedicinesViewModel
import com.tbmedtrack.app.util.ScheduleUtil
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tbmedtrack.app.ui.home.HomeViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TreatmentScreen(
    onOpenTimeline: () -> Unit = {},
    onViewSchedule: (Long) -> Unit = {},
    medsVm: MedicinesViewModel = viewModel(),
    homeVm: HomeViewModel = viewModel()
) {
    val meds by medsVm.medicines.collectAsStateWithLifecycle()
    val home by homeVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { homeVm.refresh() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val phaseCards by androidx.compose.runtime.produceState(
        initialValue = emptyList<PhaseCardInfo>(), meds
    ) {
        val repo = com.tbmedtrack.app.ServiceLocator.medRepository(context)
        val today = ScheduleUtil.today()
        val list = mutableListOf<PhaseCardInfo>()
        for (mws in meds) {
            val phases = repo.phasesFor(mws.medicine.id)
            if (phases.isEmpty()) continue
            val eval = com.tbmedtrack.app.data.ScheduleEngine.evaluate(mws.medicine, phases, today)
            val lines = phases.map { p ->
                val range = if (p.endDay != null) "Days ${p.startDay}-${p.endDay}" else "Day ${p.startDay}+"
                val sched = if (p.scheduleType == com.tbmedtrack.app.data.db.PhaseScheduleType.WEEKLY_DAYS)
                    com.tbmedtrack.app.data.ScheduleEngine.daysLabel(p.daysOfWeek) else "Daily"
                "${p.phaseName}: $range · $sched · ${p.tabletsPerDose} ${if (p.tabletsPerDose == 1) "tablet" else "tablets"}"
            }
            list += PhaseCardInfo(
                medicineId = mws.medicine.id,
                medicineName = mws.medicine.name,
                currentPhase = eval.phaseName.ifBlank { "—" },
                treatmentDay = eval.treatmentDay,
                phaseDayCount = eval.phaseDayCount,
                todayTablets = eval.tablets,
                scheduledToday = eval.scheduled,
                phaseLines = lines
            )
        }
        value = list
    }

    val tbMeds = meds.filter { it.medicine.partOfTbRegimen }

    if (tbMeds.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.MedicalServices,
            title = "No treatment set up",
            subtitle = "Add medicines and mark them as part of your treatment to track your regimen here."
        )
        return
    }

    val startDay = tbMeds.minOfOrNull { it.medicine.startDate }
    val startDate = startDay?.let { LocalDate.ofEpochDay(it) }
    val fmt = DateTimeFormatter.ofPattern("MMMM d, yyyy")

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item {
            SectionCard {
                Text("TREATMENT PROGRESS", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("Day ${home.treatmentDay}", style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                if (startDate != null) {
                    Text("Treatment tracking started", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(startDate.format(fmt), style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(8.dp))
                Text("${home.treatmentDay} days tracked", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenTimeline,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("View full treatment timeline") }
            }
        }

        item {
            SectionCard {
                Text("MY TREATMENT", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text("Current regimen (${tbMeds.size} ${if (tbMeds.size == 1) "medicine" else "medicines"})",
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                tbMeds.forEach { RegimenRow(it) }
            }
        }

        items(phaseCards) { pc -> PhaseCard(pc, onViewSchedule = onViewSchedule) }

        item {
            Text(
                "MedTrack does not decide which medicines or doses are correct. " +
                    "Enter and follow the treatment prescribed by your healthcare professional.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class PhaseCardInfo(
    val medicineId: Long,
    val medicineName: String,
    val currentPhase: String,
    val treatmentDay: Int,
    val phaseDayCount: Int?,
    val todayTablets: Int,
    val scheduledToday: Boolean,
    val phaseLines: List<String>
)

@Composable
private fun PhaseCard(pc: PhaseCardInfo, onViewSchedule: (Long) -> Unit) {
    SectionCard {
        Text(pc.medicineName.uppercase(), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        val dayLabel = if (pc.phaseDayCount != null) "Day ${pc.treatmentDay} / ${pc.phaseDayCount}"
        else "Day ${pc.treatmentDay}"
        Text("${pc.currentPhase} · $dayLabel", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (pc.scheduledToday) "Today's dose: ${pc.todayTablets} ${if (pc.todayTablets == 1) "tablet" else "tablets"}"
            else "Not scheduled today",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(10.dp))
        pc.phaseLines.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.OutlinedButton(
            onClick = { onViewSchedule(pc.medicineId) },
            modifier = Modifier.fillMaxWidth()
        ) { Text("View full schedule") }
    }
}

@Composable
private fun RegimenRow(m: MedicineWithSchedules) {
    val times = m.schedules.sortedBy { it.timeMinutes }
        .joinToString(" • ") { ScheduleUtil.formatTime(it.timeMinutes) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Text("💊") }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.medicine.name, style = MaterialTheme.typography.titleMedium)
            Text("${m.medicine.dose} ${m.medicine.unit}${if (times.isNotEmpty()) " • $times" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
