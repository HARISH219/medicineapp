package com.tbmedtrack.app.ui.cloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.sync.SyncPhase
import com.tbmedtrack.app.sync.SyncStatus
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.theme.DarkOnSurfaceMuted
import com.tbmedtrack.app.ui.theme.StatusMissed
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.ui.theme.StatusUpcoming
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("hh:mm:ss a")

@Composable
fun CloudSyncScreen(vm: CloudSyncViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.status

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item { Text("Cloud Sync", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface) }

        // Overall status card (always active — no enable/disable).
        item {
            SectionCard {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("☁️", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        val (dot, label) = phaseLabel(s.phase)
                        Text("$dot $label", style = MaterialTheme.typography.titleMedium,
                            color = phaseColor(s.phase), fontWeight = FontWeight.SemiBold)
                        Text("Cloud sync is always active", style = MaterialTheme.typography.bodySmall, color = DarkOnSurfaceMuted)
                    }
                }
                Spacer(Modifier.height(10.dp))
                StatusRow("Cloud", if (s.configured) "🟢 Connected" else "⚪ Not set up")
                StatusRow("Last upload", clockOrDash(s.lastUploadAt))
                StatusRow("Last download", clockOrDash(s.lastDownloadAt))
                StatusRow("Cloud version", if (s.cloudVersion > 0) "#${s.cloudVersion}" else "—")
                StatusRow("Pending changes", s.pendingCount.toString())
                StatusRow("Devices", "${s.connectedDevices} connected")
            }
        }

        // First-time connect (only until a backend URL is configured). No toggle.
        if (!s.configured) {
            item {
                SectionCard {
                    Text("Connect this device", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Enter your backend URL once to turn on multi-device sync. Reminders always " +
                            "run locally regardless. After connecting, sync stays on automatically.",
                        style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = state.backendUrl,
                        onValueChange = { vm.setUrl(it) },
                        label = { Text("https://your-app.vercel.app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.message?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.connect() },
                        enabled = !state.connecting,
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text(if (state.connecting) "Connecting…" else "Connect") }
                }
            }
        }

        // The single CHECK SYNC button (verify, not enable/disable).
        item {
            Button(
                onClick = { vm.checkSync() },
                enabled = !state.checking && s.configured,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (state.checking) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Checking…")
                } else Text("CHECK SYNC")
            }
        }

        // CHECK SYNC result.
        state.checkResult?.let { result ->
            item {
                SectionCard {
                    Text("☁️ SYNC CHECK", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(10.dp))
                    result.lines.forEach { line ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(if (line.ok) "✓" else "⚠", color = if (line.ok) StatusTaken else StatusUpcoming, modifier = Modifier.width(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(line.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                                if (line.detail.isNotBlank()) {
                                    Text(line.detail, style = MaterialTheme.typography.bodySmall, color = DarkOnSurfaceMuted)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Last synchronized: ${clockOrDash(result.checkedAtMillis)}", style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Status: ${result.overallLabel}",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (result.overallOk) StatusTaken else StatusUpcoming,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        item {
            Text(
                "Everything syncs automatically whenever there's a connection. You never need to " +
                    "press sync after recording a medicine — CHECK SYNC just verifies delivery.",
                style = MaterialTheme.typography.bodySmall, color = DarkOnSurfaceMuted
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
    }
}

private fun phaseLabel(phase: SyncPhase): Pair<String, String> = when (phase) {
    SyncPhase.SYNCED -> "🟢" to "Synced"
    SyncPhase.SYNCING -> "🟡" to "Syncing…"
    SyncPhase.OFFLINE -> "🔵" to "Waiting for connection"
    SyncPhase.ERROR -> "🔴" to "Sync problem"
    SyncPhase.LOCAL_ONLY -> "⚪" to "Local only (not set up)"
}

@Composable
private fun phaseColor(phase: SyncPhase) = when (phase) {
    SyncPhase.SYNCED -> StatusTaken
    SyncPhase.SYNCING -> StatusUpcoming
    SyncPhase.OFFLINE -> com.tbmedtrack.app.ui.theme.AccentBlue
    SyncPhase.ERROR -> StatusMissed
    SyncPhase.LOCAL_ONLY -> DarkOnSurfaceMuted
}

private fun clockOrDash(millis: Long): String {
    if (millis <= 0) return "—"
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFmt)
}
