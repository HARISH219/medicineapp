package com.tbmedtrack.app.ui.monitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.sync.MonitorSnapshot
import com.tbmedtrack.app.sync.SyncCheckResult
import com.tbmedtrack.app.sync.SyncStatus
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read-only status of a single medicine occurrence as projected onto the monitor. */
enum class MonitorStatus { TAKEN, UPCOMING, NOT_TAKEN }

/** One medicine within a dose group. */
data class MonitoredMedicine(
    val occurrenceId: String,
    val name: String,
    val doseText: String,
    val takenAtMillis: Long?,
    val status: MonitorStatus
)

/** A group of medicines that share the same scheduled time on a given day (a "dose"). */
data class MonitoredDoseGroup(
    val epochDay: Long,
    val timeMinutes: Int,
    val scheduledMillis: Long,
    val label: String,          // "Morning Dose" / "Night Dose" / "Midday Dose" ...
    val emoji: String,          // ☀️ / 🌙 / ...
    val medicines: List<MonitoredMedicine>,
    val status: MonitorStatus,  // rolled-up group status
) {
    val takenCount: Int get() = medicines.count { it.status == MonitorStatus.TAKEN }
    val total: Int get() = medicines.size
    val tabletsText: String get() {
        val n = medicines.size
        return "$n ${if (n == 1) "medicine" else "medicines"}"
    }
    /** representative "taken at" for a fully-taken group */
    val takenAtMillis: Long? get() = medicines.mapNotNull { it.takenAtMillis }.maxOrNull()
}

/** The soonest not-taken medicine, for the "Next medicine" card. */
data class NextMedicine(
    val name: String,
    val doseText: String,
    val scheduledMillis: Long,
    val timeMinutes: Int,
    val status: MonitorStatus
)

/** A food record shown read-only, with the primary-authored eligibility time. */
data class MonitoredFood(
    val uuid: String,
    val foodTimeMillis: Long,
    val gapMinutes: Int
) {
    val eligibleMillis: Long? get() = if (gapMinutes > 0) foodTimeMillis + gapMinutes * 60_000L else null
}

/** Per-day roll-up used to paint the calendar. */
enum class DayStatus { COMPLETED, UPCOMING, MISSED, NONE }

data class MonitorUiState(
    val loading: Boolean = true,
    /** all dose groups across the published window, sorted by time */
    val groups: List<MonitoredDoseGroup> = emptyList(),
    val foods: List<MonitoredFood> = emptyList(),
    val next: NextMedicine? = null,
    val dayStatus: Map<Long, DayStatus> = emptyMap(),
    val emergencyContact: String = "",
    val sync: SyncStatus = SyncStatus(),
    val checkingSync: Boolean = false,
    val lastCheck: SyncCheckResult? = null,
    val nextCheckLabel: String = "",
    val errorMessage: String? = null,
    /** ticks every second so countdowns update; carries "now" */
    val nowMillis: Long = System.currentTimeMillis()
) {
    fun groupsForDay(epochDay: Long): List<MonitoredDoseGroup> =
        groups.filter { it.epochDay == epochDay }.sortedBy { it.timeMinutes }
    val todayEpochDay: Long get() = ScheduleUtil.today().toEpochDay()
    val todayGroups: List<MonitoredDoseGroup> get() = groupsForDay(todayEpochDay)
}

/**
 * Read-only monitor controller. All schedule/history/food data comes from the concrete snapshot
 * published by the PRIMARY device; this device never creates a schedule and never mutates data.
 */
class MonitorViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = ServiceLocator.settingsRepository(app)
    private val syncManager = ServiceLocator.syncManager(app)
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state.asStateFlow()

    private val pollIntervalMs = 10 * 60_000L
    private val minValid = 946684800000L  // 2000-01-01
    private val maxValid = 4102444800000L // 2100-01-01

    init {
        observeSync()
        observeSnapshot()
        startPolling()
        startClockTicker()
    }

    fun refresh() { viewModelScope.launch { runRefresh() } }

    fun checkSync() {
        viewModelScope.launch {
            _state.update { it.copy(checkingSync = true, errorMessage = null) }
            try {
                val result = syncManager.checkSync()
                recompute(syncManager.monitorSnapshot.value)
                _state.update { it.copy(lastCheck = result) }
            } catch (t: Throwable) {
                _state.update { it.copy(errorMessage = t.message ?: "Could not check sync") }
            } finally {
                _state.update { it.copy(checkingSync = false) }
            }
        }
    }

    private fun observeSync() {
        viewModelScope.launch {
            syncManager.status.collect { sync -> _state.update { it.copy(sync = sync) } }
        }
    }

    private fun observeSnapshot() {
        viewModelScope.launch {
            syncManager.monitorSnapshot.collect { snapshot ->
                runCatching { recompute(snapshot) }.onFailure { error ->
                    _state.update {
                        it.copy(loading = false, errorMessage = error.message ?: "Could not display monitor data")
                    }
                }
            }
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                runRefresh()
                _state.update { it.copy(nextCheckLabel = clock(System.currentTimeMillis() + pollIntervalMs)) }
                delay(pollIntervalMs)
            }
        }
    }

    /** Drives live countdowns and DUE/OVERDUE transitions without extra network calls. */
    private fun startClockTicker() {
        viewModelScope.launch {
            while (isActive) {
                delay(1_000L)
                val now = System.currentTimeMillis()
                _state.update { it.copy(nowMillis = now) }
                // Re-derive statuses roughly once a minute (cheap; snapshot is in memory).
                if (now / 60_000L != _state.value.nowMillis / 60_000L) {
                    runCatching { recompute(syncManager.monitorSnapshot.value) }
                }
            }
        }
    }

    private suspend fun runRefresh() = refreshMutex.withLock {
        try {
            syncManager.reconfigure()
            syncManager.syncNow()
            recompute(syncManager.monitorSnapshot.value)
            _state.update { it.copy(errorMessage = null) }
        } catch (t: Throwable) {
            _state.update { it.copy(loading = false, errorMessage = t.message ?: "Sync temporarily unavailable") }
        }
    }

    private fun statusOf(status: String, takenAtMillis: Long?, criticalAt: Long, now: Long): MonitorStatus {
        val taken = status == DoseStatus.TAKEN.name
        if (taken) return MonitorStatus.TAKEN
        val missed = status == DoseStatus.MISSED.name || status == DoseStatus.SKIPPED.name || now >= criticalAt
        return if (missed) MonitorStatus.NOT_TAKEN else MonitorStatus.UPCOMING
    }

    private fun doseLabel(timeMinutes: Int): Pair<String, String> {
        val hour = timeMinutes / 60
        return when {
            hour < 5 -> "Night Dose" to "🌙"
            hour < 11 -> "Morning Dose" to "☀️"
            hour < 15 -> "Midday Dose" to "🌤️"
            hour < 18 -> "Afternoon Dose" to "🌇"
            else -> "Night Dose" to "🌙"
        }
    }

    private suspend fun recompute(snapshot: MonitorSnapshot) {
        val localContact = runCatching { settings.settings.first().emergencyContact }.getOrDefault("")
        val contact = snapshot.emergencyContact.ifBlank { localContact }
        val now = System.currentTimeMillis()

        // 1) Valid per-medicine occurrences.
        data class Occ(
            val occurrenceId: String, val name: String, val doseText: String,
            val epochDay: Long, val timeMinutes: Int, val scheduledMillis: Long,
            val takenAt: Long?, val status: MonitorStatus
        )
        val occs = snapshot.doses.mapNotNull { d ->
            if (d.scheduledAt !in minValid until maxValid) return@mapNotNull null
            val tm = runCatching {
                ScheduleUtil.localDateTime(d.scheduledAt).toLocalTime().let { it.hour * 60 + it.minute }
            }.getOrNull() ?: return@mapNotNull null
            Occ(
                occurrenceId = d.occurrenceId,
                name = d.medicineName.ifBlank { "Medicine" },
                doseText = d.doseText,
                epochDay = d.scheduledEpochDay,
                timeMinutes = tm,
                scheduledMillis = d.scheduledAt,
                takenAt = d.takenAt,
                status = statusOf(d.status, d.takenAt, d.criticalAt, now)
            )
        }.distinctBy { it.occurrenceId }

        // 2) Group by (day, time).
        val groups = occs.groupBy { it.epochDay to it.timeMinutes }.map { (key, list) ->
            val (label, emoji) = doseLabel(key.second)
            val meds = list.sortedBy { it.name }.map {
                MonitoredMedicine(it.occurrenceId, it.name, it.doseText, it.takenAt, it.status)
            }
            val groupStatus = when {
                meds.all { it.status == MonitorStatus.TAKEN } -> MonitorStatus.TAKEN
                meds.any { it.status == MonitorStatus.NOT_TAKEN } -> MonitorStatus.NOT_TAKEN
                else -> MonitorStatus.UPCOMING
            }
            MonitoredDoseGroup(
                epochDay = key.first,
                timeMinutes = key.second,
                scheduledMillis = list.first().scheduledMillis,
                label = label,
                emoji = emoji,
                medicines = meds,
                status = groupStatus
            )
        }.sortedWith(compareBy({ it.epochDay }, { it.timeMinutes }))

        // 3) Next medicine: soonest not-taken occurrence at/after now.
        val next = occs
            .filter { it.status != MonitorStatus.TAKEN && it.scheduledMillis >= now - 60_000L }
            .minByOrNull { it.scheduledMillis }
            ?.let { NextMedicine(it.name, it.doseText, it.scheduledMillis, it.timeMinutes, it.status) }

        // 4) Per-day calendar status.
        val dayStatus = groups.groupBy { it.epochDay }.mapValues { (_, dayGroups) ->
            when {
                dayGroups.all { it.status == MonitorStatus.TAKEN } -> DayStatus.COMPLETED
                dayGroups.any { it.status == MonitorStatus.NOT_TAKEN } -> DayStatus.MISSED
                else -> DayStatus.UPCOMING
            }
        }

        // 5) Food (deduped, newest first).
        val foods = snapshot.foodEvents
            .filter { it.foodAt in minValid until maxValid }
            .distinctBy { it.uuid }
            .sortedByDescending { it.foodAt }
            .map { MonitoredFood(it.uuid, it.foodAt, it.gapMinutes) }

        _state.update {
            it.copy(
                loading = false,
                groups = groups,
                foods = foods,
                next = next,
                dayStatus = dayStatus,
                emergencyContact = contact,
                errorMessage = null
            )
        }
    }

    private fun clock(millis: Long): String = runCatching {
        val lt = ScheduleUtil.localDateTime(millis).toLocalTime()
        ScheduleUtil.formatTime(lt.hour * 60 + lt.minute)
    }.getOrDefault("—")
}
