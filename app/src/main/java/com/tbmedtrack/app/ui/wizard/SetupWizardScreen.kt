package com.tbmedtrack.app.ui.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.ui.components.ScreenBackground
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.theme.AccentPurple
import com.tbmedtrack.app.ui.theme.DarkOnSurfaceMuted
import com.tbmedtrack.app.ui.theme.DueOrange
import com.tbmedtrack.app.ui.theme.StatusMissed
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.util.PermissionUtil

private enum class WizardStep { WELCOME, PERMISSIONS, SOUND, VERIFY, DEVICE_TYPE, CONNECT }

/**
 * First-launch setup wizard: welcome -> permission checklist -> alarm sound test -> verification
 * -> device type (Main / Secondary) -> (secondary) connect code. Uses Android's supported
 * settings intents; it never claims to bypass OS restrictions.
 *
 * [onMainChosen] runs after the user finishes as a Main device; [onSecondaryConnected] after a
 * Secondary device links to the main account.
 */
@Composable
fun SetupWizardScreen(
    onRequestNotificationPermission: () -> Unit,
    onMainChosen: () -> Unit,
    onSecondaryConnected: () -> Unit,
    vm: SetupWizardViewModel = viewModel()
) {
    val context = LocalContext.current
    val perms by vm.perms.collectAsStateWithLifecycle()
    val connect by vm.connect.collectAsStateWithLifecycle()
    var step by remember { mutableStateOf(WizardStep.WELCOME) }

    // Re-check permission state whenever we come back to the screen (e.g. from system settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val stepIndex = when (step) {
        WizardStep.WELCOME -> 0
        WizardStep.PERMISSIONS -> 1
        WizardStep.SOUND -> 2
        WizardStep.VERIFY -> 3
        WizardStep.DEVICE_TYPE, WizardStep.CONNECT -> 4
    }

    ScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 28.dp)
        ) {
            ProgressDots(stepIndex, total = 5)
            Spacer(Modifier.height(24.dp))

            when (step) {
                WizardStep.WELCOME -> WelcomeStep(onNext = { step = WizardStep.PERMISSIONS })
                WizardStep.PERMISSIONS -> PermissionsStep(
                    perms = perms,
                    onAllowNotifications = { onRequestNotificationPermission() },
                    onExactAlarms = { PermissionUtil.exactAlarmSettingsIntent(context)?.let { context.startActivity(it) } },
                    onFullScreen = { context.startActivity(PermissionUtil.notificationSettingsIntent(context)) },
                    onBattery = { runCatching { context.startActivity(PermissionUtil.batteryOptimizationSettingsIntent()) } },
                    onRefresh = { vm.refreshPermissions() },
                    onNext = { step = WizardStep.SOUND }
                )
                WizardStep.SOUND -> SoundStep(
                    onTest = { vm.playTestAlarm() },
                    onNext = { vm.stopTestAlarm(); step = WizardStep.VERIFY }
                )
                WizardStep.VERIFY -> VerifyStep(perms = perms, onContinue = { step = WizardStep.DEVICE_TYPE })
                WizardStep.DEVICE_TYPE -> DeviceTypeStep(
                    onMain = { vm.chooseMain(onMainChosen) },
                    onSecondary = { step = WizardStep.CONNECT }
                )
                WizardStep.CONNECT -> ConnectStep(
                    connect = connect,
                    onConnect = { code -> vm.connectSecondary(code, onSecondaryConnected) },
                    onBack = { step = WizardStep.DEVICE_TYPE }
                )
            }
        }
    }
}

@Composable
private fun ProgressDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(total) { i ->
            Box(
                Modifier
                    .size(if (i == current) 12.dp else 9.dp)
                    .background(
                        if (i <= current) AccentPurple else DarkOnSurfaceMuted.copy(alpha = 0.35f),
                        CircleShape
                    )
            )
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column {
        Text("🔔", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            "Never miss your medicine.",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Set up reliable reminders, alarms and device synchronization.",
            style = MaterialTheme.typography.bodyLarge,
            color = DarkOnSurfaceMuted
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Get Started →", onNext)
    }
}

@Composable
private fun PermissionsStep(
    perms: WizardPermissionState,
    onAllowNotifications: () -> Unit,
    onExactAlarms: () -> Unit,
    onFullScreen: () -> Unit,
    onBattery: () -> Unit,
    onRefresh: () -> Unit,
    onNext: () -> Unit
) {
    Column {
        StepTitle("Reminder permissions", "Grant these so reminders and critical alarms work reliably. We only use Android's supported settings — nothing is bypassed.")
        Spacer(Modifier.height(16.dp))
        PermRow("Notification permission", "Required to remind you when medicine is due.", perms.notifications, "Allow Notifications", onAllowNotifications)
        Spacer(Modifier.height(10.dp))
        PermRow("Precise alarms", "Schedules reminders at their exact configured times.", perms.exactAlarms, "Enable Precise Alarms", onExactAlarms)
        Spacer(Modifier.height(10.dp))
        PermRow("Critical alarm access", "Shows an urgent alarm screen even when locked.", perms.fullScreen, "Enable Alarm Access", onFullScreen)
        Spacer(Modifier.height(10.dp))
        PermRow("Background activity / battery", "Some phones restrict background apps. This can be limited by your device.", perms.battery, "Configure", onBattery)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRefresh) { Text("Re-check status") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Your phone or manufacturer may still restrict background activity. We use Android's supported alarm APIs wherever possible.",
            style = MaterialTheme.typography.bodySmall,
            color = DarkOnSurfaceMuted
        )
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Continue", onNext)
    }
}

@Composable
private fun PermRow(
    title: String,
    body: String,
    granted: Boolean,
    action: String,
    onClick: () -> Unit
) {
    SectionCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (granted) "✓" else "○", color = if (granted) StatusTaken else DarkOnSurfaceMuted, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(body, style = MaterialTheme.typography.bodySmall, color = DarkOnSurfaceMuted)
            }
        }
        if (!granted) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
        } else {
            Spacer(Modifier.height(6.dp))
            Text("✓ Enabled", color = StatusTaken, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SoundStep(onTest: () -> Unit, onNext: () -> Unit) {
    var heard by remember { mutableStateOf<Boolean?>(null) }
    Column {
        StepTitle("Alarm sound", "Critical medicine alarms use your device's alarm/notification sound. System volume, Do Not Disturb and manufacturer settings may affect how it behaves.")
        Spacer(Modifier.height(16.dp))
        SectionCard {
            Text("🔊 Medicine Alarm", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { onTest(); heard = null }, modifier = Modifier.fillMaxWidth()) { Text("Test Alarm") }
            Spacer(Modifier.height(12.dp))
            Text("Did you hear the alarm?", style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { heard = true }, modifier = Modifier.weight(1f)) { Text("YES ✓") }
                OutlinedButton(onClick = { heard = false }, modifier = Modifier.weight(1f)) { Text("NO") }
            }
            if (heard == false) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Check your alarm/notification volume, Do Not Disturb, and this app's notification settings.",
                    style = MaterialTheme.typography.bodySmall, color = StatusMissed
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Continue", onNext)
    }
}

@Composable
private fun VerifyStep(perms: WizardPermissionState, onContinue: () -> Unit) {
    Column {
        StepTitle("Your reminder system", "A quick summary. You can change any of these later in Settings.")
        Spacer(Modifier.height(16.dp))
        SectionCard {
            CheckLine("Notifications", perms.notifications)
            CheckLine("Precise alarms", perms.exactAlarms)
            CheckLine("Critical alarm access", perms.fullScreen)
            CheckLine("Background activity / battery", perms.battery)
            CheckLine("Alarm sound", true)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Continue", onContinue)
    }
}

@Composable
private fun CheckLine(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓" else "⚠", color = if (ok) StatusTaken else StatusUpcomingColor(), modifier = Modifier.width(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatusUpcomingColor() = com.tbmedtrack.app.ui.theme.StatusUpcoming

@Composable
private fun DeviceTypeStep(onMain: () -> Unit, onSecondary: () -> Unit) {
    Column {
        StepTitle("How will you use this app?", "Choose this device's role. You can re-pair later in Settings.")
        Spacer(Modifier.height(16.dp))
        RoleCard(
            emoji = "📱",
            title = "Main Device",
            subtitle = "Your personal medicine device",
            body = "Receives medicine notifications, reminders, alarms and critical alerts.",
            bullets = listOf(
                "Medicine notifications", "Food reminders", "Critical alarms",
                "Full reminder interface", "Medicine taken controls", "Sync with secondary devices"
            ),
            button = "Use as Main Device",
            accent = DueOrange,
            onClick = onMain
        )
        Spacer(Modifier.height(14.dp))
        RoleCard(
            emoji = "👀",
            title = "Secondary Device",
            subtitle = "Monitoring device",
            body = "A trusted device that monitors whether a scheduled medicine has been taken.",
            bullets = listOf(
                "Synchronized medicine status", "Critical missed-medicine alerts",
                "Red critical monitoring screen", "Call button",
                "No normal medicine notifications", "No routine medicine alarms"
            ),
            button = "Use as Secondary Device",
            accent = AccentPurple,
            onClick = onSecondary
        )
    }
}

@Composable
private fun RoleCard(
    emoji: String,
    title: String,
    subtitle: String,
    body: String,
    bullets: List<String>,
    button: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
        Spacer(Modifier.height(10.dp))
        bullets.forEach {
            Text("•  $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = androidx.compose.ui.graphics.Color.White)
        ) { Text(button, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun ConnectStep(
    connect: ConnectState,
    onConnect: (String) -> Unit,
    onBack: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    Column {
        StepTitle("Connect to Main Device", "Enter the connection code generated on your Main Device (Settings → Authorized devices).")
        Spacer(Modifier.height(16.dp))
        SectionCard {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().take(9) },
                placeholder = { Text("XXXX-XXXX") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (connect.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(connect.error, color = StatusMissed, style = MaterialTheme.typography.bodyMedium)
            }
            if (connect.connected) {
                Spacer(Modifier.height(8.dp))
                Text("✓ Connected to Main Device", color = StatusTaken, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onConnect(code) },
                enabled = !connect.connecting,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple, contentColor = androidx.compose.ui.graphics.Color.White)
            ) { Text(if (connect.connecting) "Connecting…" else "Connect", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(6.dp))
            Text(
                "A backend must be configured for pairing. If you haven't set one up, use this as a Main Device instead.",
                style = MaterialTheme.typography.bodySmall, color = DarkOnSurfaceMuted
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("← Back") }
    }
}

@Composable
private fun StepTitle(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = DarkOnSurfaceMuted)
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = DueOrange, contentColor = androidx.compose.ui.graphics.Color.White)
    ) { Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
}
