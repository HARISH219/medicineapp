package com.tbmedtrack.app.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.home.HomeViewModel
import com.tbmedtrack.app.ui.theme.StatusMissed
import com.tbmedtrack.app.ui.theme.StatusMissedContainer
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.ui.theme.StatusTakenContainer
import com.tbmedtrack.app.util.ScheduleUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only monitoring dashboard for an authorized family/monitor device. It shows the
 * current recorded status of the primary person's dose events. It never allows changing
 * the regimen. With a backend deployed, this reflects live cloud state.
 */
@Composable
fun MonitorScreen(personName: String = "Medication", vm: HomeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    val timeFmt = DateTimeFormatter.ofPattern("hh:mm a")

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item {
            Text("$personName's medication", style = MaterialTheme.typography.headlineMedium)
            Text("TODAY", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        val morning = state.morningCombination
        if (morning == null) {
            item {
                SectionCard { Text("No dose scheduled today.", style = MaterialTheme.typography.bodyLarge) }
            }
        } else {
            item {
                val allTaken = morning.doses.all { it.status == DoseStatus.TAKEN }
                SectionCard {
                    Text(
                        ScheduleUtil.formatTime(morning.timeMinutes),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text("TB Medication", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    if (allTaken) {
                        val at = morning.doses.mapNotNull { it.takenAtMillis }.maxOrNull()
                        Text("✓ TAKEN", color = StatusTaken, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                        at?.let {
                            val t = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)
                            Text("Recorded at $t", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text("🚨 Not recorded", color = StatusMissed, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium)
                        Text("Today's ${morning.totalCount}-medicine combination has not been recorded as taken.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Text(
                "This is a read-only monitoring view. It reflects the recorded status only and " +
                    "does not change the treatment.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
