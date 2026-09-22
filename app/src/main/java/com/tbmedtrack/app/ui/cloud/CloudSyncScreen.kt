package com.tbmedtrack.app.ui.cloud

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.theme.StatusTaken

@Composable
fun CloudSyncScreen(vm: CloudSyncViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.clearMessage() }
    }

    LazyColumn(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 96.dp)
    ) {
        item { Text("Cloud sync", style = MaterialTheme.typography.headlineMedium) }

        item {
            SectionCard {
                Text(
                    "Enter your backend URL to turn on multi-device sync. Until you do, the app works " +
                        "fully offline on this device only. Your medication reminders always run locally " +
                        "regardless of sync.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard {
                Text("Backend URL", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.backendUrl,
                    onValueChange = { vm.setUrl(it) },
                    label = { Text("https://your-app.vercel.app") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                if (state.configured) {
                    Text("Status: connected", color = StatusTaken,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                } else {
                    Text("Status: not connected (local only)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.saveAndTest() },
                    enabled = !state.testing,
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text(if (state.testing) "Testing…" else "Save & connect") }
                if (state.configured) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { vm.disconnect() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Disconnect", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            SectionCard {
                Text("After connecting", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Go to Settings → Authorized devices to generate a 6-digit code, then enter it on " +
                        "your family member's phone to let them monitor your medication status.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
