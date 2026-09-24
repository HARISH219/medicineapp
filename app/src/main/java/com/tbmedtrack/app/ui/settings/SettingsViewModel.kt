package com.tbmedtrack.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.BackupManager
import com.tbmedtrack.app.data.db.AppDatabase
import com.tbmedtrack.app.data.settings.AppSettings
import com.tbmedtrack.app.data.settings.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = ServiceLocator.settingsRepository(app)
    private val scheduler = ServiceLocator.alarmScheduler(app)
    private val backup = BackupManager(app)

    val settingsFlow: StateFlow<AppSettings> =
        settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settings.setTheme(mode) }
    fun setReminders(enabled: Boolean) = viewModelScope.launch {
        settings.setReminders(enabled)
        if (enabled) scheduler.rescheduleAll()
    }
    fun setSound(enabled: Boolean) = viewModelScope.launch { settings.setSound(enabled) }
    fun setVibration(enabled: Boolean) = viewModelScope.launch { settings.setVibration(enabled) }
    fun setSnooze(minutes: Int) = viewModelScope.launch { settings.setSnooze(minutes) }
    fun setWeekStartsMonday(monday: Boolean) = viewModelScope.launch { settings.setWeekStartsMonday(monday) }
    fun setNightMedicineMinutes(m: Int) = viewModelScope.launch {
        settings.setNightMedicineMinutes(m)
        ServiceLocator.criticalAlarmScheduler(getApplication()).rescheduleTodayAndFuture()
    }
    fun setNotifyNotRecorded(v: Boolean) = viewModelScope.launch { settings.setNotifyNotRecorded(v) }
    fun setNotifyTaken(v: Boolean) = viewModelScope.launch { settings.setNotifyTaken(v) }
    fun setNotifyCritical(v: Boolean) = viewModelScope.launch { settings.setNotifyCritical(v) }
    fun setReduceMotion(v: Boolean) = viewModelScope.launch { settings.setReduceMotion(v) }
    fun setEscalationInterval(m: Int) = viewModelScope.launch {
        settings.setEscalationIntervalMinutes(m)
        ServiceLocator.criticalAlarmScheduler(getApplication()).rescheduleTodayAndFuture()
    }
    fun setCriticalStartDelay(m: Int) = viewModelScope.launch {
        settings.setCriticalStartDelayMinutes(m)
        ServiceLocator.criticalAlarmScheduler(getApplication()).rescheduleTodayAndFuture()
    }
    fun setDefaultFoodGap(m: Int) = viewModelScope.launch {
        settings.setDefaultFoodGapMinutes(m)
        // Recompute food-gap chains for today with the new default.
        ServiceLocator.foodGapScheduler(getApplication()).rescheduleForToday()
    }
    fun setCriticalGrace(m: Int) = viewModelScope.launch {
        settings.setCriticalGraceMinutes(m)
        ServiceLocator.foodGapScheduler(getApplication()).rescheduleForToday()
        ServiceLocator.criticalAlarmScheduler(getApplication()).rescheduleTodayAndFuture()
    }
    fun setEmergencyContact(number: String) = viewModelScope.launch { settings.setEmergencyContact(number) }

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        val result = backup.export(uri)
        _message.value = result.fold(
            onSuccess = { "Backup exported ($it medicines)" },
            onFailure = { "Export failed: ${it.message}" }
        )
    }

    fun importBackup(uri: Uri) = viewModelScope.launch {
        val result = backup.import(uri)
        _message.value = result.fold(
            onSuccess = {
                scheduler.rescheduleAll()
                "Backup imported ($it medicines)"
            },
            onFailure = { "Import failed: ${it.message}" }
        )
    }

    fun deleteAllData() = viewModelScope.launch {
        val db = AppDatabase.get(getApplication())
        db.logDao().deleteAll()
        db.auditDao().deleteAll()
        db.syncOperationDao().deleteAll()
        db.medicineDao().getAllMedicines().forEach { db.medicineDao().deleteMedicine(it) }
        _message.value = "All data deleted"
    }

    fun clearMessage() { _message.value = null }
}
