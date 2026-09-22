package com.tbmedtrack.app.ui.devices

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.db.Device
import com.tbmedtrack.app.data.db.DeviceRole
import com.tbmedtrack.app.sync.AuthCodeManager
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.theme.StatusTaken

@Composable
fun DevicesScreen(vm: DevicesViewModel = viewModel()) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var enteredCode by remember { mutableStateOf("") }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.clearMessage()
        }
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item { Text("Authorized devices", style = MaterialTheme.typography.headlineMedium) }

        if (!ui.backendConfigured) {
            item {
                SectionCard {
                    Text("Cloud sync not configured", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Multi-device sync and monitoring need a small backend that holds your Turso " +
                            "token securely (it must never be shipped inside the app). See docs/BACKEND.md " +
                            "for setup. Until then, this device works fully offline on its own.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionCard {
                Text("Connect another device", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                ui.authCode?.let { code ->
                    Text("DEVICE AUTHORIZATION", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        AuthCodeManager.formatted(code.code),
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 40.sp
                    )
                    Text("Expires in 5 minutes. Enter this on the new device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                }
                Button(onClick = { vm.generateAuthCode() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Generate authorization code")
                }
            }
        }

        item {
            SectionCard {
                Text("Join an existing account", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("Enter the 6-digit code shown on the primary device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = enteredCode,
                    onValueChange = { if (it.length <= 6 && it.all { c -> c.isDigit() }) enteredCode = it },
                    label = { Text("Authorization code") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { vm.redeemCode(enteredCode, android.os.Build.MODEL ?: "Device") },
                    enabled = enteredCode.length == 6,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Connect") }
            }
        }

        item { Text("Devices", style = MaterialTheme.typography.titleLarge) }

        items(devices) { device ->
            DeviceRow(device, onRevoke = { vm.revoke(device.deviceId) })
        }
    }
}

@Composable
private fun DeviceRow(device: Device, onRevoke: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("📱 ${device.name}${if (device.isThisDevice) " (this device)" else ""}",
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    when (device.role) {
                        DeviceRole.PRIMARY -> "Primary"
                        DeviceRole.MONITOR -> "Monitor"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (device.online) "Online" else "Last active recently",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (device.online) StatusTaken else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!device.isThisDevice) {
                TextButton(onClick = onRevoke) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
