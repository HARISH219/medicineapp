package com.tbmedtrack.app.ui.medicines

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.MedicineWithSchedules
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MedicinesViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ServiceLocator.medRepository(app)
    private val scheduler = ServiceLocator.alarmScheduler(app)

    val medicines: StateFlow<List<MedicineWithSchedules>> =
        repo.observeMedicines().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
        )

    fun setActive(medicineId: Long, active: Boolean) {
        viewModelScope.launch {
            repo.setActive(medicineId, active)
            if (active) {
                // recreate future reminders
                val schedules = ServiceLocator.medRepository(getApplication())
                    .getMedicineWithSchedules(medicineId)?.schedules ?: emptyList()
                schedules.forEach { scheduler.scheduleNextFor(medicineId, it.id) }
            } else {
                scheduler.cancelForMedicine(medicineId)
            }
        }
    }

    fun delete(medicineId: Long) {
        viewModelScope.launch {
            scheduler.cancelForMedicine(medicineId)
            repo.deleteMedicine(medicineId)
        }
    }
}
