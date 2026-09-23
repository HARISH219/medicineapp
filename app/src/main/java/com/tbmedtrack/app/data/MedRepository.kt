package com.tbmedtrack.app.data

import com.tbmedtrack.app.data.db.DoseSchedule
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.data.db.LogDao
import com.tbmedtrack.app.data.db.Medicine
import com.tbmedtrack.app.data.db.MedicationLog
import com.tbmedtrack.app.data.db.MedicineDao
import com.tbmedtrack.app.data.db.MedicineWithSchedules
import com.tbmedtrack.app.data.db.SyncState
import com.tbmedtrack.app.data.model.DayAdherence
import com.tbmedtrack.app.data.model.DaySummary
import com.tbmedtrack.app.data.model.DoseEvent
import com.tbmedtrack.app.data.model.ScheduledDose
import com.tbmedtrack.app.data.model.Statistics
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Central repository. Generates scheduled doses deterministically from medicines +
 * schedules and merges them with recorded logs. Never fabricates history: statuses
 * come only from stored logs, with unlogged past doses treated as MISSED after cutoff.
 */
class MedRepository(
    private val medicineDao: MedicineDao,
    private val logDao: LogDao,
    private val auditDao: com.tbmedtrack.app.data.db.AuditDao,
    private val syncOperationDao: com.tbmedtrack.app.data.db.SyncOperationDao,
    private val phaseDao: com.tbmedtrack.app.data.db.PhaseDao
) {

    /** Cache of phases per medicine for a single getDosesForDay pass. */
    suspend fun phasesFor(medicineId: Long) = phaseDao.forMedicine(medicineId)

    /** grace window (minutes) after scheduled time before a dose counts as due/missed. */
    private val missedCutoffMinutes = 60
    /** delay beyond which a taken dose is counted as "late". */
    private val lateThresholdMinutes = 30

    /** Set by the app so recorded events carry the authoring device id. */
    @Volatile var currentDeviceId: String = ""

    /**
     * Epoch day from which active tracking/reminders begin. Unlogged doses strictly before
     * this day are NOT counted as missed (they may be historical, or pre-tracking). Set by the
     * app from settings; 0 = treat every scheduled day as tracked.
     */
    @Volatile var trackingStartDay: Long = 0L

    /** Optional hook invoked after an event is written locally, used to queue sync. */
    @Volatile var onEventRecorded: ((MedicationLog) -> Unit)? = null

    fun observeMedicines(): Flow<List<MedicineWithSchedules>> =
        medicineDao.observeMedicinesWithSchedules()

    suspend fun getMedicineWithSchedules(id: Long) = medicineDao.getMedicineWithSchedules(id)

    suspend fun getAllMedicinesWithSchedules() = medicineDao.getAllMedicinesWithSchedules()

    suspend fun upsertMedicine(medicine: Medicine, schedules: List<DoseSchedule>): Long {
        val id = if (medicine.id == 0L) medicineDao.insertMedicine(medicine)
        else { medicineDao.updateMedicine(medicine); medicine.id }
        // Replace schedule set (historical logs are never touched).
        medicineDao.deleteSchedulesForMedicine(id)
        schedules.forEach { s ->
            medicineDao.insertSchedule(s.copy(id = 0, medicineId = id))
        }
        return id
    }

    /** Replace the phase set for a medicine (used when configuring a phased prescription). */
    suspend fun savePhases(medicineId: Long, phases: List<com.tbmedtrack.app.data.db.MedicationPhase>) {
        phaseDao.deleteForMedicine(medicineId)
        phases.forEachIndexed { i, p ->
            phaseDao.insert(p.copy(id = 0, medicineId = medicineId, orderIndex = i))
        }
    }

    suspend fun setActive(medicineId: Long, active: Boolean) =
        medicineDao.setActive(medicineId, active)

    suspend fun deleteMedicine(medicineId: Long) = medicineDao.deleteMedicineById(medicineId)

    suspend fun getMedicine(id: Long) = medicineDao.getMedicine(id)

    // --- Dose generation ---

    /** Build the list of scheduled doses for a specific day, merged with logs. */
    suspend fun getDosesForDay(date: LocalDate): List<ScheduledDose> {
        val epochDay = date.toEpochDay()
        val meds = medicineDao.getAllMedicinesWithSchedules()
        val logs = logDao.getLogsForDay(epochDay)
        val now = System.currentTimeMillis()
        val result = mutableListOf<ScheduledDose>()

        for (mws in meds) {
            val med = mws.medicine
            // Phased medicines (Bedaquiline etc.) are driven by the ScheduleEngine, which
            // decides whether a dose is scheduled today and the exact tablet count.
            val phases = phaseDao.forMedicine(med.id)
            val phased = phases.isNotEmpty()
            val eval = if (phased) ScheduleEngine.evaluate(med, phases, date) else null

            for (sch in mws.schedules) {
                val scheduledMillis = ScheduleUtil.toEpochMillis(date, sch.timeMinutes)
                val existing = logs.firstOrNull {
                    it.medicineId == med.id && it.scheduleId == sch.id &&
                        it.scheduledDateTime == scheduledMillis
                }

                // Decide if this dose occurs today.
                val occursToday = if (phased) eval!!.scheduled
                else ScheduleUtil.appliesOn(med, sch, date)

                // A non-scheduled phased day still surfaces if it was historically logged
                // (so history/edits remain consistent); otherwise skip it entirely (no reminder).
                if (!occursToday && existing == null) continue

                val tablets = when {
                    existing != null && existing.tabletsScheduled > 0 -> existing.tabletsScheduled
                    phased -> eval!!.tablets
                    else -> 0
                }
                val phaseName = existing?.phaseName?.ifBlank { eval?.phaseName ?: "" } ?: (eval?.phaseName ?: "")
                val doseText = if (tablets > 0) "$tablets ${if (tablets == 1) "tablet" else "tablets"}"
                else "${med.dose} ${med.unit}"

                val status = existing?.status ?: run {
                    val trackedDay = trackingStartDay == 0L || epochDay >= trackingStartDay
                    val cutoff = scheduledMillis + missedCutoffMinutes * 60_000L
                    if (trackedDay && now > cutoff) DoseStatus.MISSED else DoseStatus.SCHEDULED
                }
                result += ScheduledDose(
                    medicineId = med.id,
                    scheduleId = sch.id,
                    medicineName = existing?.medicineName?.ifBlank { med.name } ?: med.name,
                    doseText = existing?.doseText?.ifBlank { doseText } ?: doseText,
                    timeMinutes = sch.timeMinutes,
                    scheduledMillis = scheduledMillis,
                    epochDay = epochDay,
                    status = status,
                    takenAtMillis = existing?.actualTakenDateTime,
                    snoozeCount = existing?.snoozeCount ?: 0,
                    foodTiming = med.foodTiming,
                    notes = med.notes,
                    logId = existing?.id,
                    historical = existing?.historical ?: false,
                    takenTimePrecision = existing?.takenTimePrecision
                        ?: com.tbmedtrack.app.data.db.TakenTimePrecision.EXACT,
                    tabletsScheduled = tablets,
                    phaseName = phaseName,
                    treatmentDay = eval?.treatmentDay ?: 0,
                    phaseDayCount = eval?.phaseDayCount
                )
            }
        }
        return result.sortedWith(compareBy({ it.timeMinutes }, { it.medicineName }))
    }

    /** Next scheduled dose (date + tablets) for a phased medicine after today. */
    suspend fun nextScheduledDoseFor(medicineId: Long, fromDate: LocalDate): ScheduleEngine.NextDose? {
        val med = medicineDao.getMedicine(medicineId) ?: return null
        val phases = phaseDao.forMedicine(medicineId)
        if (phases.isEmpty()) return null
        return ScheduleEngine.nextScheduledDose(med, phases, fromDate)
    }

    /** Group doses that share the same clock time into dose events. */
    fun groupIntoEvents(doses: List<ScheduledDose>): List<DoseEvent> =
        doses.groupBy { it.timeMinutes }
            .map { (time, list) ->
                DoseEvent(
                    timeMinutes = time,
                    scheduledMillis = list.first().scheduledMillis,
                    epochDay = list.first().epochDay,
                    doses = list
                )
            }
            .sortedBy { it.timeMinutes }

    /**
     * Central write path: insert or update an event, stamping device + sync metadata, then
     * record an audit entry and enqueue a sync operation. [action] and [operationType] describe
     * the change for the audit trail and the sync queue.
     */
    private suspend fun writeEvent(
        row: MedicationLog,
        action: String,
        operationType: String,
        oldStatus: com.tbmedtrack.app.data.db.DoseStatus = com.tbmedtrack.app.data.db.DoseStatus.SCHEDULED
    ) {
        val now = System.currentTimeMillis()
        val stamped = row.copy(
            deviceId = row.deviceId.ifBlank { currentDeviceId },
            updatedAt = now,
            syncState = SyncState.PENDING
        )
        val existing = logDao.getByUuid(stamped.uuid)
        val finalUuid: String
        if (existing != null) {
            logDao.update(stamped.copy(id = existing.id))
            finalUuid = stamped.uuid
        } else {
            val newId = logDao.insert(stamped)
            if (newId == -1L) {
                val conflict = logDao.findLog(stamped.medicineId, stamped.scheduleId, stamped.scheduledDateTime)
                if (conflict != null) {
                    logDao.update(stamped.copy(id = conflict.id, uuid = conflict.uuid))
                    finalUuid = conflict.uuid
                } else finalUuid = stamped.uuid
            } else finalUuid = stamped.uuid
        }

        auditDao.insert(
            com.tbmedtrack.app.data.db.MedicationEventAudit(
                eventUuid = finalUuid,
                action = action,
                oldStatus = oldStatus.name,
                newStatus = stamped.status.name,
                deviceId = currentDeviceId
            )
        )
        syncOperationDao.enqueue(
            com.tbmedtrack.app.data.db.SyncOperation(
                deviceId = currentDeviceId,
                recordId = finalUuid,
                operationType = operationType
            )
        )
        onEventRecorded?.invoke(stamped)
    }

    /**
     * Revert an accidental "taken". Preserves the event row (never deleted): records a
     * TAKEN -> REVERTED audit entry, then sets the live status back to SCHEDULED (PENDING) so
     * reminders can re-arm. Returns true if a taken dose was reverted.
     */
    suspend fun revertEvent(epochDay: Long, timeMinutes: Int): Boolean {
        val date = LocalDate.ofEpochDay(epochDay)
        val doses = getDosesForDay(date).filter { it.timeMinutes == timeMinutes }
        var any = false
        for (dose in doses) {
            val existing = logDao.findLog(dose.medicineId, dose.scheduleId, dose.scheduledMillis) ?: continue
            if (existing.status != DoseStatus.TAKEN) continue
            // Audit the revert explicitly (old = TAKEN, new = REVERTED marker) then set live PENDING.
            auditDao.insert(
                com.tbmedtrack.app.data.db.MedicationEventAudit(
                    eventUuid = existing.uuid,
                    action = com.tbmedtrack.app.data.db.AuditAction.REVERT,
                    oldStatus = DoseStatus.TAKEN.name,
                    newStatus = DoseStatus.REVERTED.name,
                    deviceId = currentDeviceId
                )
            )
            val reverted = existing.copy(
                status = DoseStatus.SCHEDULED,
                actualTakenDateTime = null,
                historical = false,
                updatedAt = System.currentTimeMillis(),
                syncState = SyncState.PENDING
            )
            logDao.update(reverted)
            syncOperationDao.enqueue(
                com.tbmedtrack.app.data.db.SyncOperation(
                    deviceId = currentDeviceId,
                    recordId = existing.uuid,
                    operationType = com.tbmedtrack.app.data.db.OperationType.MEDICATION_REVERTED
                )
            )
            onEventRecorded?.invoke(reverted)
            any = true
        }
        return any
    }

    /** Persist a taken record for a single dose, creating the row if needed. */
    suspend fun markTaken(dose: ScheduledDose, takenAt: Long = System.currentTimeMillis()) {
        val existing = logDao.findLog(dose.medicineId, dose.scheduleId, dose.scheduledMillis)
        writeEvent(
            (existing ?: logRow(dose, DoseStatus.TAKEN, takenAt))
                .copy(status = DoseStatus.TAKEN, actualTakenDateTime = takenAt),
            action = com.tbmedtrack.app.data.db.AuditAction.MARK_TAKEN,
            operationType = com.tbmedtrack.app.data.db.OperationType.MEDICATION_TAKEN,
            oldStatus = existing?.status ?: DoseStatus.SCHEDULED
        )
    }

    suspend fun markStatus(dose: ScheduledDose, status: DoseStatus) {
        val existing = logDao.findLog(dose.medicineId, dose.scheduleId, dose.scheduledMillis)
        val takenAt = if (status == DoseStatus.TAKEN) System.currentTimeMillis() else null
        writeEvent(
            (existing ?: logRow(dose, status, takenAt)).copy(status = status, actualTakenDateTime = takenAt),
            action = com.tbmedtrack.app.data.db.AuditAction.MARK_TAKEN,
            operationType = com.tbmedtrack.app.data.db.OperationType.MEDICATION_TAKEN,
            oldStatus = existing?.status ?: DoseStatus.SCHEDULED
        )
    }

    suspend fun recordSnooze(medicineId: Long, scheduleId: Long, scheduledMillis: Long) {
        val date = ScheduleUtil.localDateTime(scheduledMillis).toLocalDate()
        val med = medicineDao.getMedicine(medicineId) ?: return
        val existing = logDao.findLog(medicineId, scheduleId, scheduledMillis)
        if (existing != null) {
            if (existing.status == DoseStatus.TAKEN) return
            writeEvent(
                existing.copy(status = DoseStatus.SNOOZED, snoozeCount = existing.snoozeCount + 1),
                action = com.tbmedtrack.app.data.db.AuditAction.SNOOZE,
                operationType = com.tbmedtrack.app.data.db.OperationType.MEDICATION_SNOOZED,
                oldStatus = existing.status
            )
        } else {
            writeEvent(
                MedicationLog(
                    medicineId = medicineId,
                    scheduleId = scheduleId,
                    scheduledDateTime = scheduledMillis,
                    scheduledEpochDay = date.toEpochDay(),
                    status = DoseStatus.SNOOZED,
                    snoozeCount = 1,
                    medicineName = med.name,
                    doseText = "${med.dose} ${med.unit}"
                ),
                action = com.tbmedtrack.app.data.db.AuditAction.SNOOZE,
                operationType = com.tbmedtrack.app.data.db.OperationType.MEDICATION_SNOOZED
            )
        }
    }

    /** Mark a single medicine dose taken by ids (used from notification/alarm action). */
    suspend fun markTakenByIds(medicineId: Long, scheduleId: Long, scheduledMillis: Long) {
        val med = medicineDao.getMedicine(medicineId) ?: return
        val date = ScheduleUtil.localDateTime(scheduledMillis).toLocalDate()
        val existing = logDao.findLog(medicineId, scheduleId, scheduledMillis)
        val now = System.currentTimeMillis()
        writeEvent(
            (existing ?: MedicationLog(
                medicineId = medicineId,
                scheduleId = scheduleId,
                scheduledDateTime = scheduledMillis,
                scheduledEpochDay = date.toEpochDay(),
                medicineName = med.name,
                doseText = "${med.dose} ${med.unit}"
            )).copy(status = DoseStatus.TAKEN, actualTakenDateTime = now),
            action = com.tbmedtrack.app.data.db.AuditAction.MARK_TAKEN,
            operationType = com.tbmedtrack.app.data.db.OperationType.MEDICATION_TAKEN,
            oldStatus = existing?.status ?: DoseStatus.SCHEDULED
        )
    }

    /**
     * Import previously-taken doses over an inclusive epoch-day range as historical TAKEN
     * events. Never fabricates a time: [precision] chooses EXACT/APPROX/UNKNOWN and only a
     * known/approx time is stored. Does not create reminders for these past dates.
     */
    suspend fun importHistoricalRange(
        startEpochDay: Long,
        endEpochDay: Long,
        precision: String = com.tbmedtrack.app.data.db.TakenTimePrecision.UNKNOWN
    ): Int {
        var count = 0
        var day = startEpochDay
        while (day <= endEpochDay) {
            val date = LocalDate.ofEpochDay(day)
            val doses = getDosesForDay(date)
            for (dose in doses) {
                if (dose.status == DoseStatus.TAKEN) continue
                val existing = logDao.findLog(dose.medicineId, dose.scheduleId, dose.scheduledMillis)
                val takenAt = when (precision) {
                    com.tbmedtrack.app.data.db.TakenTimePrecision.UNKNOWN -> null
                    else -> dose.scheduledMillis // approx == scheduled time; not invented exact
                }
                writeEvent(
                    (existing ?: logRow(dose, DoseStatus.TAKEN, takenAt)).copy(
                        status = DoseStatus.TAKEN,
                        actualTakenDateTime = takenAt,
                        historical = true,
                        takenTimePrecision = precision
                    ),
                    action = com.tbmedtrack.app.data.db.AuditAction.IMPORT_HISTORICAL,
                    operationType = com.tbmedtrack.app.data.db.OperationType.MEDICATION_HISTORICAL,
                    oldStatus = existing?.status ?: DoseStatus.SCHEDULED
                )
                count++
            }
            day++
        }
        return count
    }

    /** Mark all doses at a given time on a day as taken. Returns the taken timestamp. */
    suspend fun markEventTaken(epochDay: Long, timeMinutes: Int): Long {
        val date = LocalDate.ofEpochDay(epochDay)
        val doses = getDosesForDay(date).filter { it.timeMinutes == timeMinutes }
        val now = System.currentTimeMillis()
        doses.forEach { markTaken(it, now) }
        return now
    }

    private fun logRow(dose: ScheduledDose, status: DoseStatus, takenAt: Long?) = MedicationLog(
        medicineId = dose.medicineId,
        scheduleId = dose.scheduleId,
        scheduledDateTime = dose.scheduledMillis,
        scheduledEpochDay = dose.epochDay,
        actualTakenDateTime = takenAt,
        status = status,
        snoozeCount = dose.snoozeCount,
        medicineName = dose.medicineName,
        doseText = dose.doseText,
        tabletsScheduled = dose.tabletsScheduled,
        phaseName = dose.phaseName,
        notes = dose.notes
    )

    // --- Summaries & statistics ---

    suspend fun getDaySummary(date: LocalDate): DaySummary {
        val doses = getDosesForDay(date)
        val scheduled = doses.size
        val taken = doses.count { it.status == DoseStatus.TAKEN }
        val missed = doses.count { it.status == DoseStatus.MISSED }
        val pendingOrUpcoming = doses.count {
            it.status == DoseStatus.SCHEDULED || it.status == DoseStatus.SNOOZED
        }
        val adherence = when {
            scheduled == 0 -> DayAdherence.NONE
            missed == 0 && taken == scheduled -> DayAdherence.ALL_TAKEN
            missed >= 2 -> DayAdherence.MISSED
            missed >= 1 -> DayAdherence.PARTIAL
            // Nothing missed yet, some doses still scheduled/upcoming: neutral, not partial.
            pendingOrUpcoming > 0 && taken == 0 -> DayAdherence.NONE
            else -> DayAdherence.PARTIAL
        }
        return DaySummary(date.toEpochDay(), scheduled, taken, missed, adherence)
    }

    suspend fun getMonthSummaries(year: Int, month: Int): Map<Long, DaySummary> {
        val first = LocalDate.of(year, month, 1)
        val last = first.withDayOfMonth(first.lengthOfMonth())
        val map = LinkedHashMap<Long, DaySummary>()
        var d = first
        while (!d.isAfter(last)) {
            map[d.toEpochDay()] = getDaySummary(d)
            d = d.plusDays(1)
        }
        return map
    }

    suspend fun getLogsForMedicine(medicineId: Long) = logDao.getLogsForMedicine(medicineId)

    private val regimenEngine = RegimenEngine()

    /** Combinations (grouped by clock time) required today. */
    suspend fun getCombinationsForDay(date: LocalDate): List<RegimenEngine.Combination> =
        regimenEngine.combinationsFor(getDosesForDay(date))

    /** True if any dose at [timeMinutes] on [epochDay] is not yet marked taken. */
    suspend fun isEventPending(epochDay: Long, timeMinutes: Int): Boolean {
        val date = LocalDate.ofEpochDay(epochDay)
        val doses = getDosesForDay(date).filter { it.timeMinutes == timeMinutes }
        return doses.isNotEmpty() && doses.any { it.status != DoseStatus.TAKEN }
    }

    /** All distinct scheduled clock-times (minutes) on a given day. */
    suspend fun scheduledTimesFor(date: LocalDate): List<Int> =
        getDosesForDay(date).map { it.timeMinutes }.distinct().sorted()

    /**
     * Compute statistics over a date range [startDay, endDay] inclusive (epoch days),
     * plus lifetime totals and streaks. Deterministic, from schedules + logs only.
     */
    suspend fun computeStatistics(rangeStart: LocalDate, rangeEnd: LocalDate): Statistics {
        var scheduled = 0
        var taken = 0
        var missed = 0
        var late = 0
        var delaySum = 0L
        var delayCount = 0

        var d = rangeStart
        while (!d.isAfter(rangeEnd)) {
            val doses = getDosesForDay(d)
            for (dose in doses) {
                scheduled++
                when (dose.status) {
                    DoseStatus.TAKEN -> {
                        taken++
                        val delay = ((dose.takenAtMillis ?: dose.scheduledMillis) - dose.scheduledMillis) / 60000L
                        if (delay > lateThresholdMinutes) late++
                        if (delay > 0) { delaySum += delay; delayCount++ }
                    }
                    DoseStatus.MISSED -> missed++
                    else -> {}
                }
            }
            d = d.plusDays(1)
        }
        val adherence = if (scheduled == 0) 0.0 else (taken * 10000L / scheduled) / 100.0

        // Lifetime totals and streaks from all logs + full history
        val allLogs = logDao.getAllLogs()
        val totalTaken = allLogs.count { it.status == DoseStatus.TAKEN }
        val totalMissed = allLogs.count { it.status == DoseStatus.MISSED }
        val completedDoseEvents = allLogs.filter { it.status == DoseStatus.TAKEN }
            .map { it.scheduledEpochDay to it.scheduledDateTime }.distinct().size

        val earliest = logDao.earliestLoggedDay()
        val daysTracked = if (earliest == null) 0
        else (ScheduleUtil.today().toEpochDay() - earliest + 1).toInt().coerceAtLeast(0)

        val (current, best) = computeStreaks(earliest)

        val avgDelay = if (delayCount == 0) 0 else (delaySum / delayCount).toInt()

        return Statistics(
            scheduled = scheduled,
            taken = taken,
            missed = missed,
            late = late,
            adherencePercent = adherence,
            currentStreak = current,
            bestStreak = best,
            daysTracked = daysTracked,
            completedDoseEvents = completedDoseEvents,
            totalTaken = totalTaken,
            totalMissed = totalMissed,
            averageDelayMinutes = avgDelay
        )
    }

    /**
     * Streak = consecutive days (ending today or yesterday) where every scheduled
     * dose was taken. Best streak is the longest such run over tracked history.
     */
    /** Per-day adherence classification for streak math. */
    private enum class DayKind { PERFECT, IMPERFECT, NO_SCHEDULE }

    private suspend fun computeStreaks(earliest: Long?): Pair<Int, Int> {
        if (earliest == null) return 0 to 0
        val todayDay = ScheduleUtil.today().toEpochDay()

        val kinds = mutableListOf<DayKind>()
        var day = earliest
        while (day <= todayDay) {
            val summary = getDaySummary(LocalDate.ofEpochDay(day))
            kinds += when {
                summary.scheduled == 0 -> DayKind.NO_SCHEDULE
                summary.taken == summary.scheduled -> DayKind.PERFECT
                else -> DayKind.IMPERFECT
            }
            day++
        }

        // Best streak: longest run of PERFECT days. NO_SCHEDULE days are neutral and
        // neither extend nor break a run.
        var best = 0
        var run = 0
        for (k in kinds) {
            when (k) {
                DayKind.PERFECT -> { run++; if (run > best) best = run }
                DayKind.IMPERFECT -> run = 0
                DayKind.NO_SCHEDULE -> { /* neutral */ }
            }
        }

        // Current streak: walk backwards from today. Skip trailing NO_SCHEDULE days,
        // count PERFECT days, stop at the first IMPERFECT day.
        var currentStreak = 0
        for (i in kinds.indices.reversed()) {
            when (kinds[i]) {
                DayKind.PERFECT -> currentStreak++
                DayKind.NO_SCHEDULE -> { /* neutral, keep going */ }
                DayKind.IMPERFECT -> break
            }
        }
        return currentStreak to best
    }
}
