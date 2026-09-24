package com.tbmedtrack.app.ui.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.AppDatabase
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One monitored dose the Secondary device is watching. */
data class MonitoredDose(
    val medicineName: String,
    val scheduledMillis: Long,
    val timeMinutes: Int,
    val taken: Boolean,
    val takenAtMillis: Long?,
    /** critical = past the critical threshold and not taken */
    val critical: Boolean
)

data class MonitorUiState(
    val loading: Boolean = true,
    val doses: List<MonitoredDose> = emptyList(),
    val emergencyContact: String = "",
    val lastSyncedLabel: String = "",
    /** true if at least one monitored dose is in the critical (not-taken, overdue) state */
    val anyCritical: Boolean = false
)

/**
 * Drives the Secondary/monitoring screen. It is STATE-DRIVEN: it periodically syncs and then
 * recomputes the monitored state from stored (synced) events + the local schedule, so it
 * survives restarts, time changes, and background. It never schedules reminders or alarms.
 *
 * Critical state comes from synced events: an event that is MISSED (the Main device's critical
 * marker) or is past the critical threshold and not TAKEN is shown as critical. A synced TAKEN
 * event clears it automatically.
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = ServiceLocator.settingsRepository(app)
    private val syncManager = ServiceLocator.syncManager(app)
    private val repo = ServiceLocator.medRepository(app)

    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    private val criticalThresholdMs = 60 * 60_000L // 1h past schedule = critical for monitoring

    init { startPolling() }

    /** Sync + recompute now (also called on resume). */
    fun refresh() {
        viewModelScope.launch {
            runCatching { syncManager.reconfigure(); syncManager.syncNow() }
            recompute()
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                runCatching { syncManager.reconfigure(); syncManager.syncNow() }
                recompute()
                delay(60_000L) // poll once a minute; state-driven, not a running timer
            }
        }
    }

    private suspend fun recompute() {
        val contact = runCatching { settings.settings.first().emergencyContact }.getOrDefault("")
        val today = ScheduleUtil.today()
        val now = System.currentTimeMillis()

        // Prefer synced event rows for today (the Secondary may have no local schedule). Fall back
        // to the schedule generator if this device also has the schedule (e.g. same account).
        val logs = AppDatabase.get(getApplication()).logDao().getLogsForDay(today.toEpochDay())
        val monitored = if (logs.isNotEmpty()) {
            logs.map { log ->
                val taken = log.status == DoseStatus.TAKEN
                val critical = !taken && (
                    log.status == DoseStatus.MISSED ||
                        now > log.scheduledDateTime + criticalThresholdMs
                    )
                MonitoredDose(
                    medicineName = log.medicineName.ifBlank { "Medicine" },
                    scheduledMillis = log.scheduledDateTime,
                    timeMinutes = ScheduleUtil.localDateTime(log.scheduledDateTime)
                        .toLocalTime().let { it.hour * 60 + it.minute },
                    taken = taken,
                    takenAtMillis = log.actualTakenDateTime,
                    critical = critical
                )
            }
        } else {
            // No synced events yet: derive from the local schedule if present.
            runCatching { repo.getDosesForDay(today) }.getOrDefault(emptyList()).map { d ->
                val taken = d.status == DoseStatus.TAKEN
                MonitoredDose(
                    medicineName = d.medicineName,
                    scheduledMillis = d.scheduledMillis,
                    timeMinutes = d.timeMinutes,
                    taken = taken,
                    takenAtMillis = d.takenAtMillis,
                    critical = !taken && now > d.scheduledMillis + criticalThresholdMs
                )
            }
        }.sortedBy { it.scheduledMillis }

        _state.value = MonitorUiState(
            loading = false,
            doses = monitored,
            emergencyContact = contact,
            lastSyncedLabel = clock(now),
            anyCritical = monitored.any { it.critical }
        )
    }

    private fun clock(millis: Long): String {
        val lt = ScheduleUtil.localDateTime(millis).toLocalTime()
        return ScheduleUtil.formatTime(lt.hour * 60 + lt.minute)
    }
}
