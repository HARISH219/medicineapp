package com.tbmedtrack.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.BuildConfig
import com.tbmedtrack.app.data.settings.ThemeMode
import com.tbmedtrack.app.ui.components.SectionCard

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenDevices: () -> Unit = {},
    onOpenMonitor: () -> Unit = {},
    onImportHistory: () -> Unit = {},
    onOpenCloudSync: () -> Unit = {},
    vm: SettingsViewModel = viewModel()
) {
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDelete by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { vm.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { pendingImportUri = it } }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearMessage()
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium) }

        item {
            SectionCard {
                Text("Notifications", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SwitchRow("🔔 Medication reminders", settings.remindersEnabled) { vm.setReminders(it) }
                SwitchRow("🔊 Reminder sound", settings.soundEnabled) { vm.setSound(it) }
                SwitchRow("📳 Vibration", settings.vibrationEnabled) { vm.setVibration(it) }
            }
        }

        item {
            SectionCard {
                Text("Reminder settings", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Default snooze time", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30).forEach { m ->
                        FilterChip(
                            selected = settings.defaultSnoozeMinutes == m,
                            onClick = { vm.setSnooze(m) },
                            label = { Text("$m min") }
                        )
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("Critical reminders", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "For your MDR-TB dose, TB MedTrack escalates to an hourly full-screen alarm " +
                        "until you press MEDICINE TAKEN. For this to work reliably, please grant " +
                        "exact-alarm and notification permissions, and exempt the app from battery " +
                        "optimization. Some phones (Xiaomi, Samsung, Oppo, etc.) may still limit " +
                        "background alarms — this cannot be guaranteed by any app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                if (!com.tbmedtrack.app.util.PermissionUtil.canScheduleExactAlarms(context)) {
                    OutlinedButton(
                        onClick = {
                            com.tbmedtrack.app.util.PermissionUtil.exactAlarmSettingsIntent(context)
                                ?.let { context.startActivity(it) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("⚠ Enable exact alarms") }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = { context.startActivity(com.tbmedtrack.app.util.PermissionUtil.notificationSettingsIntent(context)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open notification settings") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(com.tbmedtrack.app.util.PermissionUtil.batteryOptimizationSettingsIntent())
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Battery optimization settings") }
                Spacer(Modifier.height(10.dp))
                Text("Start critical alarm after", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60, 120, 180).forEach { m ->
                        FilterChip(
                            selected = settings.criticalStartDelayMinutes == m,
                            onClick = { vm.setCriticalStartDelay(m) },
                            label = { Text(minutesLabel(m)) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Repeat critical alarm every", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 45, 60, 120).forEach { m ->
                        FilterChip(
                            selected = settings.escalationIntervalMinutes == m,
                            onClick = { vm.setEscalationInterval(m) },
                            label = { Text(minutesLabel(m)) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow("Reduce motion (no flashing alert)", settings.reduceMotion) { vm.setReduceMotion(it) }
            }
        }

        item {
            SectionCard {
                Text("Family / monitor alerts", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Which updates authorized monitor devices receive (requires the sync backend).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                SwitchRow("Medication not recorded", settings.notifyMonitorNotRecorded) { vm.setNotifyNotRecorded(it) }
                SwitchRow("Critical reminder", settings.notifyMonitorCritical) { vm.setNotifyCritical(it) }
                SwitchRow("Medication taken", settings.notifyMonitorTaken) { vm.setNotifyTaken(it) }
            }
        }

        item {
            SectionCard {
                Text("Appearance", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip("☀ Light", settings.themeMode == ThemeMode.LIGHT) { vm.setTheme(ThemeMode.LIGHT) }
                    ThemeChip("🌙 Dark", settings.themeMode == ThemeMode.DARK) { vm.setTheme(ThemeMode.DARK) }
                    ThemeChip("⚙ System", settings.themeMode == ThemeMode.SYSTEM) { vm.setTheme(ThemeMode.SYSTEM) }
                }
                Spacer(Modifier.height(12.dp))
                Text("Start week on", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip("Monday", settings.weekStartsMonday) { vm.setWeekStartsMonday(true) }
                    ThemeChip("Sunday", !settings.weekStartsMonday) { vm.setWeekStartsMonday(false) }
                }
            }
        }

        item {
            SectionCard {
                Text("Devices & monitoring", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenCloudSync, modifier = Modifier.fillMaxWidth()) {
                    Text("Cloud sync")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenDevices, modifier = Modifier.fillMaxWidth()) {
                    Text("Authorized devices")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenMonitor, modifier = Modifier.fillMaxWidth()) {
                    Text("Open monitor view")
                }
            }
        }

        item {
            SectionCard {
                Text("Data", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { exportLauncher.launch("tbmedtrack-backup.json") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Upload, null); Spacer(Modifier.width(8.dp)); Text("Export backup")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Download, null); Spacer(Modifier.width(8.dp)); Text("Import backup")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onImportHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("Import previous medication history")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showDelete = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("🗑 Delete all data", color = MaterialTheme.colorScheme.error) }
            }
        }

        item {
            SectionCard {
                Text("About", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("TB MedTrack", style = MaterialTheme.typography.bodyLarge)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Text(
                    "TB MedTrack is a personal medication reminder and tracking tool. It does not " +
                        "provide medical advice or replace your healthcare professional's instructions. " +
                        "Enter and follow your prescribed medication schedule.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "All your data is stored locally on this device. Nothing is sent to any server.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete all data?") },
            text = { Text("This permanently removes all medicines, schedules and history from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteAllData(); showDelete = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } }
        )
    }

    pendingImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("Import backup?") },
            text = { Text("Importing will replace all current data on this device with the backup contents.") },
            confirmButton = {
                TextButton(onClick = { vm.importBackup(uri); pendingImportUri = null }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pendingImportUri = null }) { Text("Cancel") } }
        )
    }
}

private fun minutesLabel(m: Int): String = when {
    m < 60 -> "$m min"
    m == 60 -> "1 hour"
    m % 60 == 0 -> "${m / 60} hours"
    else -> "${m / 60}h ${m % 60}m"
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
