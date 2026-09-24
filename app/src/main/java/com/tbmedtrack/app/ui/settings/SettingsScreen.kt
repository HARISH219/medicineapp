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
import kotlinx.coroutines.launch
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
    onOpenFoodHistory: () -> Unit = {},
    onOpenMedicines: () -> Unit = {},
    vm: SettingsViewModel = viewModel()
) {
    val settings by vm.settingsFlow.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDelete by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showCustomStartDelay by remember { mutableStateOf(false) }
    var showCustomRepeat by remember { mutableStateOf(false) }
    var showCustomFoodGap by remember { mutableStateOf(false) }
    var showCustomGrace by remember { mutableStateOf(false) }

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

    // Reserve space so the last card can scroll fully clear of the floating bottom nav
    // plus the Android gesture / navigation-bar inset. Adapts to every device.
    val bottomReserve = com.tbmedtrack.app.ui.components.bottomNavContentPadding()

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = bottomReserve)
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
                Text("Medicines", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onOpenMedicines, modifier = Modifier.fillMaxWidth()) {
                    Text("My medicines")
                }
            }
        }

        item { DeviceAndContactCard(settings, context, onSetContact = { vm.setEmergencyContact(it) }, onOpenDevices = onOpenDevices) }

        item { AlertPermissionsCard(context) }

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
                val startPresets = listOf(15, 30, 45, 60, 120)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    startPresets.forEach { m ->
                        FilterChip(
                            selected = settings.criticalStartDelayMinutes == m,
                            onClick = { vm.setCriticalStartDelay(m) },
                            label = { Text(minutesLabel(m)) }
                        )
                    }
                    val startIsCustom = settings.criticalStartDelayMinutes !in startPresets
                    FilterChip(
                        selected = startIsCustom,
                        onClick = { showCustomStartDelay = true },
                        label = {
                            Text(if (startIsCustom) "Custom (${minutesLabel(settings.criticalStartDelayMinutes)})" else "Custom")
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Repeat critical alarm every", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                val repeatPresets = listOf(30, 45, 60, 120)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeatPresets.forEach { m ->
                        FilterChip(
                            selected = settings.escalationIntervalMinutes == m,
                            onClick = { vm.setEscalationInterval(m) },
                            label = { Text(minutesLabel(m)) }
                        )
                    }
                    val repeatIsCustom = settings.escalationIntervalMinutes !in repeatPresets
                    FilterChip(
                        selected = repeatIsCustom,
                        onClick = { showCustomRepeat = true },
                        label = {
                            Text(if (repeatIsCustom) "Custom (${minutesLabel(settings.escalationIntervalMinutes)})" else "Custom")
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                SwitchRow("Reduce motion (no flashing alert)", settings.reduceMotion) { vm.setReduceMotion(it) }
            }
        }

        item {
            SectionCard {
                Text("Food → medicine gap", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Default wait after eating before medicine may be taken. This follows your " +
                        "prescription — change it only if your treatment instructions say so. " +
                        "Individual medicines can override this in their own settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text("Default food → medicine gap", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                val gapPresets = listOf(0, 30, 60, 90, 120, 180)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    gapPresets.forEach { m ->
                        FilterChip(
                            selected = settings.defaultFoodGapMinutes == m,
                            onClick = { vm.setDefaultFoodGap(m) },
                            label = { Text(if (m == 0) "None" else minutesLabel(m)) }
                        )
                    }
                    val gapCustom = settings.defaultFoodGapMinutes !in gapPresets
                    FilterChip(
                        selected = gapCustom,
                        onClick = { showCustomFoodGap = true },
                        label = { Text(if (gapCustom) "Custom (${minutesLabel(settings.defaultFoodGapMinutes)})" else "Custom") }
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Critical grace after eligible time", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "The critical alarm starts this long after the food-adjusted eligible time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                val gracePresets = listOf(15, 30, 45, 60, 120)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    gracePresets.forEach { m ->
                        FilterChip(
                            selected = settings.criticalGraceMinutes == m,
                            onClick = { vm.setCriticalGrace(m) },
                            label = { Text(minutesLabel(m)) }
                        )
                    }
                    val graceCustom = settings.criticalGraceMinutes !in gracePresets
                    FilterChip(
                        selected = graceCustom,
                        onClick = { showCustomGrace = true },
                        label = { Text(if (graceCustom) "Custom (${minutesLabel(settings.criticalGraceMinutes)})" else "Custom") }
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenFoodHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("View food timing history")
                }
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

        item { CloudSyncStatusCard(context, onOpenDetails = onOpenCloudSync) }

        item {
            SectionCard {
                Text("Devices & monitoring", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
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

        item {
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text(
                    "❤️ Made With Love By Harish",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Version 1.0.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
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

    if (showCustomStartDelay) {
        CustomMinutesDialog(
            title = "Start critical alarm after",
            initial = settings.criticalStartDelayMinutes,
            onConfirm = { vm.setCriticalStartDelay(it); showCustomStartDelay = false },
            onDismiss = { showCustomStartDelay = false }
        )
    }

    if (showCustomRepeat) {
        CustomMinutesDialog(
            title = "Repeat critical alarm every",
            initial = settings.escalationIntervalMinutes,
            onConfirm = { vm.setEscalationInterval(it); showCustomRepeat = false },
            onDismiss = { showCustomRepeat = false }
        )
    }

    if (showCustomFoodGap) {
        CustomMinutesDialog(
            title = "Default food → medicine gap",
            initial = settings.defaultFoodGapMinutes,
            onConfirm = { vm.setDefaultFoodGap(it); showCustomFoodGap = false },
            onDismiss = { showCustomFoodGap = false }
        )
    }

    if (showCustomGrace) {
        CustomMinutesDialog(
            title = "Critical grace after eligible time",
            initial = settings.criticalGraceMinutes,
            onConfirm = { vm.setCriticalGrace(it); showCustomGrace = false },
            onDismiss = { showCustomGrace = false }
        )
    }
}

/** Dialog to enter a custom number of minutes (1–720). */
@Composable
private fun CustomMinutesDialog(
    title: String,
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in 1..720
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Enter minutes (1–720).", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter { it.isDigit() }.take(3) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    suffix = { Text("min") },
                    isError = text.isNotEmpty() && !valid
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { parsed?.let(onConfirm) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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

/**
 * Always-on Cloud Sync status card (no enable/disable). Shows a colored status dot, last-synced
 * time and connected-device count, a CHECK SYNC button, and opens the full details page.
 */
@Composable
private fun CloudSyncStatusCard(context: android.content.Context, onOpenDetails: () -> Unit) {
    val sync = remember { com.tbmedtrack.app.ServiceLocator.syncManager(context) }
    val status by sync.status.collectAsStateWithLifecycle()
    var checking by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // Keep status fresh while the screen is visible.
    androidx.compose.runtime.LaunchedEffect(Unit) { sync.refreshStatus() }

    val (dot, label) = when (status.phase) {
        com.tbmedtrack.app.sync.SyncPhase.SYNCED -> "🟢" to "Synced"
        com.tbmedtrack.app.sync.SyncPhase.SYNCING -> "🟡" to "Syncing…"
        com.tbmedtrack.app.sync.SyncPhase.OFFLINE -> "🔵" to "Waiting for connection"
        com.tbmedtrack.app.sync.SyncPhase.ERROR -> "🔴" to "Sync problem"
        com.tbmedtrack.app.sync.SyncPhase.LOCAL_ONLY -> "⚪" to "Local only"
    }
    val lastSynced = maxOf(status.lastUploadAt, status.lastDownloadAt)
    val lastLabel = if (lastSynced <= 0) "—"
    else java.time.Instant.ofEpochMilli(lastSynced)
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a"))

    SectionCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("☁️ Cloud Sync", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("Always active", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text("$dot $label", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("Last synced: $lastLabel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Devices: ${status.connectedDevices} connected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status.pendingCount > 0) {
            Text("Pending changes: ${status.pendingCount}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Button(
                onClick = {
                    if (!checking) {
                        checking = true
                        scope.launch { runCatching { sync.checkSync() }; checking = false }
                    }
                },
                enabled = !checking && status.configured,
                modifier = Modifier.weight(1f)
            ) { Text(if (checking) "Checking…" else "CHECK SYNC") }
            OutlinedButton(onClick = onOpenDetails, modifier = Modifier.weight(1f)) { Text("Details") }
        }
    }
}

/** Device role + emergency/trusted contact (editable) + Test Call. */
@Composable
private fun DeviceAndContactCard(
    settings: com.tbmedtrack.app.data.settings.AppSettings,
    context: android.content.Context,
    onSetContact: (String) -> Unit,
    onOpenDevices: () -> Unit
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(settings.emergencyContact) { mutableStateOf(settings.emergencyContact) }
    val roleLabel = when (settings.deviceRole) {
        com.tbmedtrack.app.data.settings.DeviceRoleValue.MAIN -> "📱 Main Device"
        com.tbmedtrack.app.data.settings.DeviceRoleValue.SECONDARY -> "👀 Secondary Device"
        else -> "Not set"
    }
    SectionCard {
        Text("Device", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("This device", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(roleLabel, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onOpenDevices, modifier = Modifier.fillMaxWidth()) { Text("Connected devices") }

        Spacer(Modifier.height(16.dp))
        Text("Emergency / trusted contact", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (editing) {
            androidx.compose.material3.OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Phone
                )
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { onSetContact(draft.trim()); editing = false }) { Text("Save") }
                OutlinedButton(onClick = { draft = settings.emergencyContact; editing = false }) { Text("Cancel") }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(settings.emergencyContact.ifBlank { "Not set" }, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { editing = true }) { Text("Edit") }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    val digits = settings.emergencyContact.filter { it.isDigit() || it == '+' }
                    if (digits.isNotBlank()) runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_DIAL,
                                android.net.Uri.parse("tel:$digits")
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("📞 Test Call") }
        }
    }
}



/**
 * Alert-permissions checklist. Shows a summary ("Ready ✓" / "needs attention ⚠") and a row per
 * required permission with its real OS state and a button to open the relevant settings screen.
 * It re-checks whenever the screen resumes (e.g. after returning from system settings).
 * It never claims the app can override silent mode / DND / manufacturer restrictions.
 */
@Composable
private fun AlertPermissionsCard(context: android.content.Context) {
    // Recompute on each resume so returning from a settings screen reflects the new state.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var refreshKey by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val items = remember(refreshKey) {
        com.tbmedtrack.app.util.PermissionUtil.alertChecklist(context)
    }
    val allReady = items.all { it.granted }

    SectionCard {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Alert permissions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                if (allReady) "Ready ✓" else "Needs attention ⚠",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = if (allReady) com.tbmedtrack.app.ui.theme.StatusTaken
                else com.tbmedtrack.app.ui.theme.StatusUpcoming
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "These improve reminder reliability. Android, Do Not Disturb, and some manufacturers " +
                "can still limit alerts — no app can fully override those.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        items.forEach { item ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(if (item.granted) "✓" else "⚠", modifier = Modifier.width(24.dp))
                Text(item.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (!item.granted && item.intent != null) {
                    TextButton(onClick = { runCatching { context.startActivity(item.intent) } }) {
                        Text(item.actionLabel)
                    }
                }
            }
        }
    }
}
