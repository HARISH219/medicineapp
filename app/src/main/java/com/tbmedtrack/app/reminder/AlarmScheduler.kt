package com.tbmedtrack.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.tbmedtrack.app.data.db.AppDatabase
import com.tbmedtrack.app.util.ScheduleUtil
import java.time.LocalDate

/**
 * Schedules exact alarms for upcoming dose events. Each schedule's next applicable
 * occurrence (from now, up to a lookahead horizon) gets one alarm. When an alarm
 * fires, the receiver reschedules the following occurrence for that schedule.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** How many days ahead to search for the next occurrence. */
    private val horizonDays = 400L

    fun canScheduleExact(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else true
    }

    private fun requestCodeFor(scheduleId: Long, scheduledMillis: Long): Int {
        // Stable per schedule occurrence
        return (scheduleId * 31 + (scheduledMillis / 60000L)).toInt()
    }

    private fun firePendingIntent(
        scheduleId: Long,
        scheduledMillis: Long,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ReminderKeys.ACTION_FIRE
            putExtra(ReminderKeys.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, scheduledMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(scheduleId, scheduledMillis),
            intent,
            flags
        )
    }

    /** Reschedule every enabled schedule of every active medicine. */
    suspend fun rescheduleAll() {
        val db = AppDatabase.get(context)
        val meds = db.medicineDao().getAllMedicinesWithSchedules()
        for (mws in meds) {
            val med = mws.medicine
            if (!med.active) {
                mws.schedules.forEach { cancel(it.id, med.startDate) } // best effort cancel of stale
                continue
            }
            for (sch in mws.schedules) {
                if (!sch.enabled) continue
                scheduleNextFor(med.id, sch.id)
            }
        }
    }

    /** Schedule the next occurrence at or after [fromMillis] for a given schedule id. */
    suspend fun scheduleNextFor(medicineId: Long, scheduleId: Long, fromMillis: Long = System.currentTimeMillis()) {
        val db = AppDatabase.get(context)
        val med = db.medicineDao().getMedicine(medicineId) ?: return
        if (!med.active) return
        val sch = db.medicineDao().getSchedule(scheduleId) ?: return
        if (!sch.enabled) return

        val next = nextOccurrenceMillis(medicineId, scheduleId, fromMillis) ?: return
        scheduleExact(scheduleId, next)
    }

    /** Compute the next dose time (epoch millis) for a schedule, or null if none within horizon. */
    suspend fun nextOccurrenceMillis(medicineId: Long, scheduleId: Long, fromMillis: Long): Long? {
        val db = AppDatabase.get(context)
        val med = db.medicineDao().getMedicine(medicineId) ?: return null
        val sch = db.medicineDao().getSchedule(scheduleId) ?: return null
        var date = ScheduleUtil.localDateTime(fromMillis).toLocalDate()
        val end = date.plusDays(horizonDays)
        while (!date.isAfter(end)) {
            if (ScheduleUtil.appliesOn(med, sch, date)) {
                val millis = ScheduleUtil.toEpochMillis(date, sch.timeMinutes)
                if (millis >= fromMillis - 1000) return millis
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun scheduleExact(scheduleId: Long, triggerAtMillis: Long) {
        val pi = firePendingIntent(
            scheduleId, triggerAtMillis,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pi
                )
            } else {
                // Fall back to an inexact alarm so reminders still fire (just not exact).
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pi
                )
            }
        } catch (se: SecurityException) {
            Log.w("AlarmScheduler", "Exact alarm denied, using inexact", se)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
    }

    /** Schedule a one-off snooze alarm. */
    fun scheduleSnooze(scheduleId: Long, triggerAtMillis: Long) {
        scheduleExact(scheduleId, triggerAtMillis)
    }

    fun cancel(scheduleId: Long, scheduledMillis: Long) {
        val pi = firePendingIntent(
            scheduleId, scheduledMillis,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pi?.let { alarmManager.cancel(it) }
    }

    /** Cancel all alarms for a medicine's schedules across a reasonable window. */
    suspend fun cancelForMedicine(medicineId: Long) {
        val db = AppDatabase.get(context)
        val schedules = db.medicineDao().getSchedulesForMedicine(medicineId)
        val today = LocalDate.now(ScheduleUtil.zone())
        for (sch in schedules) {
            // cancel occurrences over the next horizon window
            var date = today
            val end = today.plusDays(horizonDays)
            while (!date.isAfter(end)) {
                val millis = ScheduleUtil.toEpochMillis(date, sch.timeMinutes)
                cancel(sch.id, millis)
                date = date.plusDays(1)
            }
        }
    }
}
