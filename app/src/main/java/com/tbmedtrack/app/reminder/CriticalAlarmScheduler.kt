package com.tbmedtrack.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Schedules the escalating critical reminder for a required dose event:
 *  - first reminder at the scheduled time (e.g. 10:00 AM)
 *  - if not recorded, a critical alarm at +1h (e.g. 11:00 AM)
 *  - then hourly until MEDICINE TAKEN is explicitly recorded.
 *
 * Escalation index: 0 = first reminder, 1 = first critical (+1h), n = +n hours.
 */
class CriticalAlarmScheduler(private val context: Context) {

    private val alarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** cap escalations so we don't alarm forever into the night. */
    private val maxEscalations = 14

    /** escalation interval in ms; loaded from settings (default 60 min). */
    @Volatile private var intervalMillis: Long = 60L * 60L * 1000L

    suspend fun refreshInterval() {
        val minutes = runCatching {
            ServiceLocator.settingsRepository(context).settings.first().escalationIntervalMinutes
        }.getOrDefault(60).coerceAtLeast(5)
        intervalMillis = minutes * 60L * 1000L
    }

    private fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    private fun requestCode(epochDay: Long, timeMinutes: Int): Int =
        ((epochDay % 100000) * 1440 + timeMinutes).toInt()

    private fun pending(
        epochDay: Long,
        timeMinutes: Int,
        scheduledMillis: Long,
        escalation: Int,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ReminderKeys.ACTION_CRITICAL
            putExtra(ReminderKeys.EXTRA_EPOCH_DAY, epochDay)
            putExtra(ReminderKeys.EXTRA_TIME_MINUTES, timeMinutes)
            putExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, scheduledMillis)
            putExtra(ReminderKeys.EXTRA_ESCALATION, escalation)
        }
        return PendingIntent.getBroadcast(
            context, requestCode(epochDay, timeMinutes) + escalation, intent, flags
        )
    }

    /** Schedule the first reminder for a dose event at its scheduled time. */
    fun scheduleEventChain(epochDay: Long, timeMinutes: Int, scheduledMillis: Long) {
        scheduleAt(epochDay, timeMinutes, scheduledMillis, escalation = 0, triggerAt = scheduledMillis)
    }

    /** Schedule the next escalation step (called after an alarm fires and the dose is still pending). */
    fun scheduleNextEscalation(epochDay: Long, timeMinutes: Int, scheduledMillis: Long, nextEscalation: Int) {
        if (nextEscalation > maxEscalations) return
        val triggerAt = scheduledMillis + nextEscalation * intervalMillis
        scheduleAt(epochDay, timeMinutes, scheduledMillis, nextEscalation, triggerAt)
    }

    private fun scheduleAt(
        epochDay: Long,
        timeMinutes: Int,
        scheduledMillis: Long,
        escalation: Int,
        triggerAt: Long
    ) {
        val pi = pending(
            epochDay, timeMinutes, scheduledMillis, escalation,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (se: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /** Cancel all escalations for an event (called when MEDICINE TAKEN is recorded). */
    fun cancelEventChain(epochDay: Long, timeMinutes: Int, scheduledMillis: Long) {
        for (e in 0..maxEscalations) {
            pending(
                epochDay, timeMinutes, scheduledMillis, e,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { alarmManager.cancel(it) }
        }
        NotificationHelper.cancelCritical(context)
    }

    /**
     * (Re)schedule critical chains for today's required dose events that are still pending.
     * Called on boot, app start, and after time changes.
     */
    suspend fun rescheduleTodayAndFuture() {
        refreshInterval()
        val repo = ServiceLocator.medRepository(context)
        val today = ScheduleUtil.today()
        // Today + next day so a late-night reboot still catches tomorrow's morning dose.
        for (offset in 0..1) {
            val date = today.plusDays(offset.toLong())
            val times = repo.scheduledTimesFor(date)
            for (t in times) {
                val scheduledMillis = ScheduleUtil.toEpochMillis(date, t)
                val pendingDose = repo.isEventPending(date.toEpochDay(), t)
                if (!pendingDose) continue
                // If the scheduled time is still in the future, start the chain at the time.
                // If it's already past, resume escalation from the appropriate hour.
                val now = System.currentTimeMillis()
                if (scheduledMillis >= now) {
                    scheduleEventChain(date.toEpochDay(), t, scheduledMillis)
                } else {
                    val stepsPast = ((now - scheduledMillis) / intervalMillis).toInt() + 1
                    scheduleNextEscalation(date.toEpochDay(), t, scheduledMillis, stepsPast.coerceAtMost(maxEscalations))
                }
            }
        }
    }
}
