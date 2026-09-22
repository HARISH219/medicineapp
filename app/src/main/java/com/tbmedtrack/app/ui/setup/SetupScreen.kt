package com.tbmedtrack.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.db.TakenTimePrecision
import com.tbmedtrack.app.ui.components.SectionCard
import com.tbmedtrack.app.ui.medicines.DatePickerDialogM3
import java.time.format.DateTimeFormatter

/**
 * First-setup / import screen. Step 1: confirm treatment start. Step 2: choose whether to
 * import previously-taken doses over a date range. Active reminders begin from today.
 */
@Composable
fun SetupScreen(onDone: () -> Unit, vm: SetupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }

    var showStart by remember { mutableStateOf(false) }
    var showFrom by remember { mutableStateOf(false) }
    var showTo by remember { mutableStateOf(false) }
    var importChosen by remember { mutableStateOf(false) }
    val fmt = DateTimeFormatter.ofPattern("MMMM d, yyyy")

    Column(
        Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Welcome to TB MedTrack", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Let's set up your treatment. You control every date and dose — the app only follows " +
                "the schedule you keep.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SectionCard {
            Text("When did your treatment begin?", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showStart = true }, modifier = Modifier.fillMaxWidth()) {
                Text(state.treatmentStart.format(fmt))
            }
        }

        SectionCard {
            Text("Previous doses", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Have you already taken the medicines from previous days? If so, import them so they " +
                    "aren't shown as missed. Active reminders will still begin from today.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = importChosen, onClick = { importChosen = true },
                    label = { Text("Yes, import history") })
                FilterChip(selected = !importChosen, onClick = { importChosen = false },
                    label = { Text("No") })
            }

            if (importChosen) {
                Spacer(Modifier.height(12.dp))
                Text("Date range already taken", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { showFrom = true }, modifier = Modifier.weight(1f)) {
                        Text("From: ${state.importFrom.format(fmt)}")
                    }
                    OutlinedButton(onClick = { showTo = true }, modifier = Modifier.weight(1f)) {
                        Text("To: ${state.importTo.format(fmt)}")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Taken time", style = MaterialTheme.typography.titleMedium)
                Text("We never invent an exact time you didn't record.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrecisionChip("Not recorded", state.precision == TakenTimePrecision.UNKNOWN) {
                        vm.setPrecision(TakenTimePrecision.UNKNOWN)
                    }
                    PrecisionChip("Approximate", state.precision == TakenTimePrecision.APPROX) {
                        vm.setPrecision(TakenTimePrecision.APPROX)
                    }
                }
            }
        }

        state.message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }

        Button(
            onClick = { if (importChosen) vm.importHistory() else vm.finishWithoutImport() },
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(if (importChosen) "Save history & continue" else "Continue",
                fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showStart) {
        DatePickerDialogM3(
            initial = state.treatmentStart,
            onDismiss = { showStart = false },
            onConfirm = { vm.setTreatmentStart(it); showStart = false }
        )
    }
    if (showFrom) {
        DatePickerDialogM3(
            initial = state.importFrom,
            onDismiss = { showFrom = false },
            onConfirm = { vm.setImportRange(it, state.importTo); showFrom = false }
        )
    }
    if (showTo) {
        DatePickerDialogM3(
            initial = state.importTo,
            onDismiss = { showTo = false },
            onConfirm = { vm.setImportRange(state.importFrom, it); showTo = false }
        )
    }
}

@Composable
private fun PrecisionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
