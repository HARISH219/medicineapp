package com.tbmedtrack.app.ui.home

import android.app.Application
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.data.model.DoseEvent
import com.tbmedtrack.app.ui.components.PillDecor
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.theme.DarkOnSurfaceMuted
import com.tbmedtrack.app.ui.theme.DueOrange
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

class EventDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ServiceLocator.medRepository(app)
    private val _event = MutableStateFlow<DoseEvent?>(null)
    val event: StateFlow<DoseEvent?> = _event.asStateFlow()

    fun load(epochDay: Long, timeMinutes: Int) {
        viewModelScope.launch {
            val doses = repo.getDosesForDay(LocalDate.ofEpochDay(epochDay))
                .filter { it.timeMinutes == timeMinutes }
            _event.value = if (doses.isEmpty()) null
            else DoseEvent(timeMinutes, doses.first().scheduledMillis, epochDay, doses)
        }
    }

    fun markTaken(epochDay: Long, timeMinutes: Int, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.markEventTaken(epochDay, timeMinutes)
            val ev = _event.value
            ev?.doses?.forEach {
                com.tbmedtrack.app.reminder.NotificationHelper.cancel(getApplication(), it.scheduledMillis)
            }
            if (ev != null) {
                ServiceLocator.criticalAlarmScheduler(getApplication())
                    .cancelEventChain(ev.epochDay, ev.timeMinutes, ev.scheduledMillis)
            }
            ServiceLocator.syncManager(getApplication()).queue()
            com.tbmedtrack.app.widget.MedTrackWidgetProvider.updateAllWidgets(getApplication())
            load(epochDay, timeMinutes)
            onDone()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    epochDay: Long,
    timeMinutes: Int,
    onBack: () -> Unit,
    vm: EventDetailViewModel = viewModel()
) {
    LaunchedEffect(epochDay, timeMinutes) { vm.load(epochDay, timeMinutes) }
    val event by vm.event.collectAsStateWithLifecycle()
    var showConfirm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val ev = event ?: return
    val completed = ev.allTaken
    val partOfDay = when {
        ev.timeMinutes < 12 * 60 -> "Morning medicines"
        ev.timeMinutes < 17 * 60 -> "Afternoon medicines"
        else -> "Night medicines"
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 40.dp)
    ) {
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp)
                            .background(DueOrange.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) { PillDecor(Modifier.size(width = 34.dp, height = 20.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            ScheduleUtil.formatTime(ev.timeMinutes),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Text(partOfDay, style = MaterialTheme.typography.bodyLarge, color = DarkOnSurfaceMuted)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${ev.medicinesCount} ${if (ev.medicinesCount == 1) "medicine" else "medicines"} • " +
                        "${ev.tabletsCount} ${if (ev.tabletsCount == 1) "tablet" else "tablets"} total",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DarkOnSurfaceMuted
                )
            }
        }

        item {
            Text(
                "Medicines in this dose",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        items(ev.doses, key = { it.medicineId }) { dose ->
            val tablets = if (dose.tabletsScheduled > 0) dose.tabletsScheduled else 1
            val taken = dose.status == DoseStatus.TAKEN
            SectionCard(contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp)
                            .background(StatusTaken.copy(alpha = if (taken) 0.18f else 0.08f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (taken) Icon(Icons.Filled.CheckCircle, null, tint = StatusTaken)
                        else PillDecor(Modifier.size(width = 26.dp, height = 16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dose.medicineName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (dose.doseText.isNotBlank()) {
                            Text(dose.doseText, style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
                        }
                    }
                    Text(
                        "$tablets ${if (tablets == 1) "tablet" else "tablets"}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (taken) StatusTaken else DueOrange,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total tablets", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "${ev.tabletsCount}",
                        style = MaterialTheme.typography.headlineMedium,
                        color = DueOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            if (completed) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.CheckCircle, null, tint = StatusTaken)
                    Spacer(Modifier.width(8.dp))
                    Text("Dose recorded — well done!", color = StatusTaken, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(
                    onClick = { showConfirm = true },
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DueOrange, contentColor = Color.White)
                ) {
                    Icon(Icons.Filled.CheckCircle, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Mark as taken", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }

    if (showConfirm) {
        ModalBottomSheet(onDismissRequest = { showConfirm = false }) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PillDecor(Modifier.size(width = 56.dp, height = 34.dp))
                Spacer(Modifier.height(12.dp))
                Text("Record this dose?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${ev.medicinesCount} ${if (ev.medicinesCount == 1) "medicine" else "medicines"} • " +
                        "${ev.tabletsCount} ${if (ev.tabletsCount == 1) "tablet" else "tablets"} at ${ScheduleUtil.formatTime(ev.timeMinutes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DarkOnSurfaceMuted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        showConfirm = false
                        vm.markTaken(epochDay, timeMinutes) {}
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = StatusTaken, contentColor = Color.White)
                ) { Text("Yes, I took it", fontWeight = FontWeight.Bold) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showConfirm = false },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Not yet") }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
