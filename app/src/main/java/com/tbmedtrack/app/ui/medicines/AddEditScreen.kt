package com.tbmedtrack.app.ui.medicines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tbmedtrack.app.data.db.DoseUnits
import com.tbmedtrack.app.data.db.Frequency
import com.tbmedtrack.app.util.ScheduleUtil
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddEditScreen(
    medicineId: Long,
    onSaved: () -> Unit,
    vm: AddEditViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(medicineId) { if (medicineId > 0) vm.load(medicineId) }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }

    var showTimePicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (medicineId > 0) "Edit medicine" else "Add medicine",
            style = MaterialTheme.typography.headlineMedium
        )

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        OutlinedTextField(
            value = state.name,
            onValueChange = { v -> vm.update { it.copy(name = v, error = null) } },
            label = { Text("Medicine name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = state.dose,
                onValueChange = { v -> vm.update { it.copy(dose = v, error = null) } },
                label = { Text("Dose") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f)
            )
            DropdownField(
                label = "Unit",
                value = state.unit,
                options = DoseUnits.ALL,
                onSelect = { v -> vm.update { it.copy(unit = v) } },
                modifier = Modifier.weight(1f)
            )
        }

        DropdownField(
            label = "Type",
            value = state.type,
            options = listOf("Tablet", "Capsule", "Syrup", "Injection", "Other"),
            onSelect = { v -> vm.update { it.copy(type = v) } },
            modifier = Modifier.fillMaxWidth()
        )

        DropdownField(
            label = "Food timing",
            value = state.foodTiming,
            options = listOf("Before food", "With food", "After food", "Doesn't matter"),
            onSelect = { v -> vm.update { it.copy(foodTiming = v) } },
            modifier = Modifier.fillMaxWidth()
        )

        // Reminder times
        Text("Reminder times", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.times.forEach { t ->
                InputChip(
                    selected = true,
                    onClick = { vm.removeTime(t) },
                    label = { Text(ScheduleUtil.formatTime(t)) },
                    trailingIcon = { Icon(Icons.Filled.Close, "Remove", Modifier.width(16.dp)) }
                )
            }
            AssistChip(
                onClick = { showTimePicker = true },
                label = { Text("Add time") },
                leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.width(16.dp)) }
            )
        }

        // Frequency
        Text("Frequency", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FreqChip("Every day", state.frequency == Frequency.EVERY_DAY) {
                vm.update { it.copy(frequency = Frequency.EVERY_DAY) }
            }
            FreqChip("Specific days", state.frequency == Frequency.SPECIFIC_DAYS) {
                vm.update { it.copy(frequency = Frequency.SPECIFIC_DAYS) }
            }
            FreqChip("Every X days", state.frequency == Frequency.EVERY_X_DAYS) {
                vm.update { it.copy(frequency = Frequency.EVERY_X_DAYS) }
            }
        }

        if (state.frequency == Frequency.SPECIFIC_DAYS) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..7).forEach { iso ->
                    val selected = state.daysOfWeek.contains(iso)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            vm.update {
                                val set = it.daysOfWeek.toMutableSet()
                                if (selected) set.remove(iso) else set.add(iso)
                                it.copy(daysOfWeek = set)
                            }
                        },
                        label = { Text(ScheduleUtil.dayOfWeekLabel(iso)) }
                    )
                }
            }
        }

        if (state.frequency == Frequency.EVERY_X_DAYS) {
            OutlinedTextField(
                value = state.intervalDays.toString(),
                onValueChange = { v -> vm.update { it.copy(intervalDays = v.toIntOrNull() ?: 1) } },
                label = { Text("Interval (days)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Dates
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showStartPicker = true }, modifier = Modifier.weight(1f)) {
                Text("Start: ${state.startDate}")
            }
            OutlinedButton(onClick = { showEndPicker = true }, modifier = Modifier.weight(1f)) {
                Text(state.endDate?.let { "End: $it" } ?: "End: none")
            }
        }
        if (state.endDate != null) {
            TextButton(onClick = { vm.update { it.copy(endDate = null) } }) { Text("Clear end date") }
        }

        OutlinedTextField(
            value = state.notes,
            onValueChange = { v -> vm.update { it.copy(notes = v) } },
            label = { Text("Notes (e.g. take with plenty of water)") },
            modifier = Modifier.fillMaxWidth()
        )

        FreqChip("Part of my TB treatment", state.partOfTbRegimen) {
            vm.update { it.copy(partOfTbRegimen = !it.partOfTbRegimen) }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = { vm.save() },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Save medicine", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(24.dp))
    }

    if (showTimePicker) {
        TimePickerDialog(
            onDismiss = { showTimePicker = false },
            onConfirm = { minutes ->
                vm.addTime(minutes)
                showTimePicker = false
            }
        )
    }
    if (showStartPicker) {
        DatePickerDialogM3(
            initial = state.startDate,
            onDismiss = { showStartPicker = false },
            onConfirm = { d -> vm.update { it.copy(startDate = d, error = null) }; showStartPicker = false }
        )
    }
    if (showEndPicker) {
        DatePickerDialogM3(
            initial = state.endDate ?: state.startDate,
            onDismiss = { showEndPicker = false },
            onConfirm = { d -> vm.update { it.copy(endDate = d, error = null) }; showEndPicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreqChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
