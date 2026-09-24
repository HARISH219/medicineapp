package com.tbmedtrack.app.ui.monitor

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.theme.DarkOnSurfaceMuted
import com.tbmedtrack.app.ui.theme.StatusMissed
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.ui.theme.StatusUpcoming
import com.tbmedtrack.app.util.ScheduleUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Secondary / monitoring device screen. It receives NO normal reminders — only synced status and
 * a prominent red CRITICAL state when a dose is overdue/not-taken. Includes a CALL button to the
 * configured trusted contact. State-driven: it syncs + recomputes, and clears automatically when
 * a TAKEN event syncs in from the Main device.
 */
@Composable
fun MonitorScreen(personName: String = "Medication", vm: MonitorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val timeFmt = DateTimeFormatter.ofPattern("hh:mm a")
    fun clock(m: Long) = Instant.ofEpochMilli(m).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)

    val critical = state.doses.filter { it.critical }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = 40.dp)
    ) {
        item {
            Column {
                Text("👀 Monitoring", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (state.anyCritical) "🔴 Attention needed" else "Monitoring: Active",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.anyCritical) StatusMissed else StatusTaken
                )
                if (state.lastSyncedLabel.isNotBlank()) {
                    Text("Last synced ${state.lastSyncedLabel}", style = MaterialTheme.typography.bodySmall, color = DarkOnSurfaceMuted)
                }
            }
        }

        // Always-on sync status + manual CHECK SYNC.
        item {
            SyncStatusCard(
                state = state,
                clock = { m -> clock(m) },
                onCheckSync = { vm.checkSync() }
            )
        }

        // Red critical cards first.
        items(critical, key = { "crit-${it.scheduledMillis}" }) { dose ->
            CriticalMonitorCard(
                medicineName = dose.medicineName,
                scheduledLabel = ScheduleUtil.formatTime(dose.timeMinutes),
                overdueText = overdueLabel(dose.scheduledMillis),
                contact = state.emergencyContact,
                onCall = { launchDial(context, state.emergencyContact) }
            )
        }

        // Non-critical status.
        val normal = state.doses.filterNot { it.critical }
        if (state.doses.isEmpty() && !state.loading) {
            item {
                SectionCard { Text("No dose recorded/scheduled today.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        items(normal, key = { "ok-${it.scheduledMillis}" }) { dose ->
            SectionCard(contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ScheduleUtil.formatTime(dose.timeMinutes), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(dose.medicineName, style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
                    }
                    if (dose.taken) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("🟢 TAKEN", color = StatusTaken, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            dose.takenAtMillis?.let { Text(clock(it), style = MaterialTheme.typography.bodySmall, color = DarkOnSurfaceMuted) }
                        }
                    } else {
                        Text("🟡 UPCOMING", color = StatusUpcoming, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        item {
            Text(
                "This monitoring device does not receive normal reminders. It shows critical " +
                    "missed-medicine alerts synced from the main device.",
                style = MaterialTheme.typography.bodySmall,
                color = DarkOnSurfaceMuted
            )
        }
    }
}

@Composable
private fun SyncStatusCard(
    state: MonitorUiState,
    clock: (Long) -> String,
    onCheckSync: () -> Unit
) {
    val s = state.sync
    val phaseLabel = when {
        !s.configured -> "⚪ Local only"
        s.phase == com.tbmedtrack.app.sync.SyncPhase.SYNCING -> "🔄 Checking…"
        s.phase == com.tbmedtrack.app.sync.SyncPhase.OFFLINE -> "🔴 Offline"
        s.phase == com.tbmedtrack.app.sync.SyncPhase.ERROR -> "🔴 Sync error"
        else -> "🟢 Up to date"
    }
    SectionCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cloud sync", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(phaseLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(8.dp))
        if (s.lastDownloadAt > 0L) {
            Text("Last downloaded ${clock(s.lastDownloadAt)}", style = MaterialTheme.typography.bodySmall, color = DarkOnSurfaceMuted)
        }
        if (s.cloudVersion > 0L) {
            Text("Cloud version #${s.cloudVersion}", style = MaterialTheme.typography.bodySmall, color = DarkOnSurfaceMuted)
        }
        if (state.nextCheckLabel.isNotBlank()) {
            Text("Next auto-check ~${state.nextCheckLabel}", style = MaterialTheme.typography.bodySmall, color = DarkOnSurfaceMuted)
        }
        state.lastCheck?.let { chk ->
            Spacer(Modifier.height(6.dp))
            Text(
                (if (chk.overallOk) "✅ " else "⚠️ ") + chk.overallLabel,
                style = MaterialTheme.typography.bodySmall,
                color = if (chk.overallOk) StatusTaken else StatusUpcoming
            )
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onCheckSync,
            enabled = !state.checkingSync,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (state.checkingSync) "Checking sync…" else "CHECK SYNC") }
    }
}

@Composable
private fun CriticalMonitorCard(
    medicineName: String,
    scheduledLabel: String,
    overdueText: String,
    contact: String,
    onCall: () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0x55EF4444), Color(0x22EF4444))), RoundedCornerShape(24.dp))
            .border(2.dp, StatusMissed, RoundedCornerShape(24.dp))
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("🔴", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(6.dp))
            Text("MEDICINE NOT TAKEN YET", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(medicineName, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text("Scheduled: $scheduledLabel", style = MaterialTheme.typography.bodyLarge, color = Color(0xEEFFFFFF))
            Text("Status: OVERDUE • $overdueText", style = MaterialTheme.typography.bodyLarge, color = Color(0xEEFFFFFF))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onCall,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = StatusMissed)
            ) { Text("📞  CALL${if (contact.isNotBlank()) "  $contact" else ""}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** Launch the phone dialer with the trusted contact (tel:); we never place the call silently. */
private fun launchDial(context: android.content.Context, number: String) {
    val digits = number.filter { it.isDigit() || it == '+' }
    if (digits.isBlank()) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$digits"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun overdueLabel(scheduledMillis: Long): String {
    val mins = ((System.currentTimeMillis() - scheduledMillis) / 60000L).coerceAtLeast(0)
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m overdue" else "${mins}m overdue"
}
