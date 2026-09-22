package com.tbmedtrack.app.data

import android.content.Context
import android.net.Uri
import com.tbmedtrack.app.data.db.AppDatabase
import com.tbmedtrack.app.data.db.DoseSchedule
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.data.db.Frequency
import com.tbmedtrack.app.data.db.Medicine
import com.tbmedtrack.app.data.db.MedicationLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** JSON backup / restore of all local data. No cloud, no server. */
class BackupManager(private val context: Context) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class BackupFile(
        val version: Int = 1,
        val exportedAt: Long = System.currentTimeMillis(),
        val medicines: List<MedicineDto>,
        val schedules: List<ScheduleDto>,
        val logs: List<LogDto>
    )

    @Serializable
    data class MedicineDto(
        val id: Long, val name: String, val dose: String, val unit: String,
        val type: String, val foodTiming: String, val notes: String,
        val active: Boolean, val partOfTbRegimen: Boolean, val createdAt: Long,
        val startDate: Long, val endDate: Long?
    )

    @Serializable
    data class ScheduleDto(
        val id: Long, val medicineId: Long, val timeMinutes: Int, val frequency: String,
        val daysOfWeek: String, val intervalDays: Int, val anchorEpochDay: Long, val enabled: Boolean
    )

    @Serializable
    data class LogDto(
        val id: Long, val medicineId: Long, val scheduleId: Long, val scheduledDateTime: Long,
        val scheduledEpochDay: Long, val actualTakenDateTime: Long?, val status: String,
        val snoozeCount: Int, val medicineName: String, val doseText: String, val notes: String
    )

    suspend fun export(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDatabase.get(context)
            val meds = db.medicineDao().getAllMedicines()
            val schedules = meds.flatMap { db.medicineDao().getSchedulesForMedicine(it.id) }
            val logs = db.logDao().getAllLogs()
            val backup = BackupFile(
                medicines = meds.map {
                    MedicineDto(
                        it.id, it.name, it.dose, it.unit, it.type, it.foodTiming, it.notes,
                        it.active, it.partOfTbRegimen, it.createdAt, it.startDate, it.endDate
                    )
                },
                schedules = schedules.map {
                    ScheduleDto(
                        it.id, it.medicineId, it.timeMinutes, it.frequency.name,
                        it.daysOfWeek, it.intervalDays, it.anchorEpochDay, it.enabled
                    )
                },
                logs = logs.map {
                    LogDto(
                        it.id, it.medicineId, it.scheduleId, it.scheduledDateTime,
                        it.scheduledEpochDay, it.actualTakenDateTime, it.status.name,
                        it.snoozeCount, it.medicineName, it.doseText, it.notes
                    )
                }
            )
            val text = json.encodeToString(backup)
            context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: error("Could not open output stream")
            meds.size
        }
    }

    /** Import replaces all existing data with the backup contents. */
    suspend fun import(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read file")
            val backup = json.decodeFromString<BackupFile>(text)
            val db = AppDatabase.get(context)

            // Clear existing
            db.logDao().deleteAll()
            db.auditDao().deleteAll()
            db.syncOperationDao().deleteAll()
            db.medicineDao().getAllMedicines().forEach { db.medicineDao().deleteMedicine(it) }

            // Restore preserving original ids
            for (m in backup.medicines) {
                db.medicineDao().insertMedicine(
                    Medicine(
                        id = m.id, name = m.name, dose = m.dose, unit = m.unit, type = m.type,
                        foodTiming = m.foodTiming, notes = m.notes, active = m.active,
                        partOfTbRegimen = m.partOfTbRegimen, createdAt = m.createdAt,
                        startDate = m.startDate, endDate = m.endDate
                    )
                )
            }
            for (s in backup.schedules) {
                db.medicineDao().insertSchedule(
                    DoseSchedule(
                        id = s.id, medicineId = s.medicineId, timeMinutes = s.timeMinutes,
                        frequency = runCatching { Frequency.valueOf(s.frequency) }.getOrDefault(Frequency.EVERY_DAY),
                        daysOfWeek = s.daysOfWeek, intervalDays = s.intervalDays,
                        anchorEpochDay = s.anchorEpochDay, enabled = s.enabled
                    )
                )
            }
            for (l in backup.logs) {
                db.logDao().insert(
                    MedicationLog(
                        id = l.id, medicineId = l.medicineId, scheduleId = l.scheduleId,
                        scheduledDateTime = l.scheduledDateTime, scheduledEpochDay = l.scheduledEpochDay,
                        actualTakenDateTime = l.actualTakenDateTime,
                        status = runCatching { DoseStatus.valueOf(l.status) }.getOrDefault(DoseStatus.SCHEDULED),
                        snoozeCount = l.snoozeCount, medicineName = l.medicineName,
                        doseText = l.doseText, notes = l.notes
                    )
                )
            }
            backup.medicines.size
        }
    }
}
