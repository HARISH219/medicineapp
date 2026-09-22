package com.tbmedtrack.app.ui.medicines

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.DoseSchedule
import com.tbmedtrack.app.data.db.Frequency
import com.tbmedtrack.app.data.db.Medicine
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AddEditState(
    val id: Long = 0,
    val name: String = "",
    val dose: String = "",
    val unit: String = "mg",
    val type: String = "Tablet",
    val foodTiming: String = "Doesn't matter",
    val notes: String = "",
    val partOfTbRegimen: Boolean = true,
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate? = null,
    val frequency: Frequency = Frequency.EVERY_DAY,
    val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7),
    val intervalDays: Int = 1,
    /** dose times in minutes-since-midnight */
    val times: List<Int> = listOf(8 * 60),
    val loading: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false
)

class AddEditViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.medRepository(app)
    private val scheduler = ServiceLocator.alarmScheduler(app)

    private val _state = MutableStateFlow(AddEditState())
    val state: StateFlow<AddEditState> = _state.asStateFlow()

    fun load(medicineId: Long) {
        if (medicineId <= 0L) return
        viewModelScope.launch {
            val mws = repo.getMedicineWithSchedules(medicineId) ?: return@launch
            val m = mws.medicine
            val freq = mws.schedules.firstOrNull()?.frequency ?: Frequency.EVERY_DAY
            val days = mws.schedules.firstOrNull()?.daysOfWeek
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
                ?: setOf(1, 2, 3, 4, 5, 6, 7)
            val interval = mws.schedules.firstOrNull()?.intervalDays ?: 1
            _state.value = AddEditState(
                id = m.id,
                name = m.name,
                dose = m.dose,
                unit = m.unit,
                type = m.type,
                foodTiming = m.foodTiming,
                notes = m.notes,
                partOfTbRegimen = m.partOfTbRegimen,
                startDate = LocalDate.ofEpochDay(m.startDate),
                endDate = m.endDate?.let { LocalDate.ofEpochDay(it) },
                frequency = freq,
                daysOfWeek = days,
                intervalDays = interval,
                times = mws.schedules.map { it.timeMinutes }.sorted().ifEmpty { listOf(8 * 60) }
            )
        }
    }

    fun update(transform: (AddEditState) -> AddEditState) {
        _state.value = transform(_state.value)
    }

    fun addTime(minutes: Int) {
        val current = _state.value.times.toMutableList()
        if (!current.contains(minutes)) {
            current.add(minutes)
            _state.value = _state.value.copy(times = current.sorted())
        }
    }

    fun removeTime(minutes: Int) {
        _state.value = _state.value.copy(times = _state.value.times.filterNot { it == minutes })
    }

    fun save() {
        val s = _state.value
        // Validation
        if (s.name.isBlank()) { _state.value = s.copy(error = "Enter a medicine name"); return }
        if (s.dose.isBlank()) { _state.value = s.copy(error = "Enter a dose"); return }
        if (s.times.isEmpty()) { _state.value = s.copy(error = "Add at least one reminder time"); return }
        if (s.endDate != null && s.endDate.isBefore(s.startDate)) {
            _state.value = s.copy(error = "End date cannot be before start date"); return
        }
        if (s.frequency == Frequency.SPECIFIC_DAYS && s.daysOfWeek.isEmpty()) {
            _state.value = s.copy(error = "Select at least one day"); return
        }
        if (s.frequency == Frequency.EVERY_X_DAYS && s.intervalDays < 1) {
            _state.value = s.copy(error = "Interval must be at least 1 day"); return
        }

        _state.value = s.copy(loading = true, error = null)
        viewModelScope.launch {
            val medicine = Medicine(
                id = s.id,
                name = s.name.trim(),
                dose = s.dose.trim(),
                unit = s.unit,
                type = s.type,
                foodTiming = s.foodTiming,
                notes = s.notes.trim(),
                active = true,
                partOfTbRegimen = s.partOfTbRegimen,
                startDate = s.startDate.toEpochDay(),
                endDate = s.endDate?.toEpochDay()
            )
            val schedules = s.times.distinct().map { t ->
                DoseSchedule(
                    medicineId = s.id,
                    timeMinutes = t,
                    frequency = s.frequency,
                    daysOfWeek = if (s.frequency == Frequency.SPECIFIC_DAYS)
                        s.daysOfWeek.sorted().joinToString(",") else "",
                    intervalDays = s.intervalDays,
                    anchorEpochDay = s.startDate.toEpochDay(),
                    enabled = true
                )
            }
            val id = repo.upsertMedicine(medicine, schedules)
            // (Re)schedule reminders
            scheduler.cancelForMedicine(id)
            val saved = repo.getMedicineWithSchedules(id)
            saved?.schedules?.forEach { scheduler.scheduleNextFor(id, it.id) }
            _state.value = _state.value.copy(loading = false, saved = true)
        }
    }
}
