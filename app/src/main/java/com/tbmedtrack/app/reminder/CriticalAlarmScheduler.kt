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

    /** repeat interval in ms between critical alarms; loaded from settings (default 60 min). */
    @Volatile private var intervalMillis: Long = 60L * 60L * 1000L
    /** delay in ms after scheduled time before the FIRST critical alarm (default 60 min). */
    @Volatile private var startDelayMillis: Long = 60L * 60L * 1000L

    suspend fun refreshInterval() {
        val s = runCatching { ServiceLocator.settingsRepository(context).settings.first() }.getOrNull()
        intervalMillis = ((s?.escalationIntervalMinutes ?: 60).coerceAtLeast(5)) * 60L * 1000L
        startDelayMillis = ((s?.criticalStartDelayMinutes ?: 60).coerceAtLeast(5)) * 60L * 1000L
    }

    /**
     * Trigger time for a given escalation index:
     *  - 0 = the normal reminder, at the scheduled time
     *  - 1 = first CRITICAL alarm, at scheduledTime + startDelay
     *  - n>=1 = scheduledTime + startDelay + (n-1) * repeatInterval
     */
    private fun triggerFor(scheduledMillis: Long, escalation: Int): Long =
        if (escalation <= 0) scheduledMillis
        else scheduledMillis + startDelayMillis + (escalation - 1) * intervalMillis

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
        scheduleAt(epochDay, timeMinutes, scheduledMillis, nextEscalation, triggerFor(scheduledMillis, nextEscalation))
    }

    /**
     * Schedule the FIRST critical alarm (escalation index 1) at an EXPLICIT time — used by the
     * food-gap system so the critical alarm starts from (foodEligible + grace) instead of the raw
     * scheduled time. Subsequent escalations repeat from there using the configured interval.
     */
    fun scheduleCriticalAt(epochDay: Long, timeMinutes: Int, scheduledMillis: Long, criticalStartMillis: Long) {
        scheduleAt(epochDay, timeMinutes, scheduledMillis, escalation = 1, triggerAt = criticalStartMillis)
    }

    /** Configured repeat interval in ms (loaded by [refreshInterval]). */
    fun intervalMillisValue(): Long = intervalMillis

    /**
     * The food-adjusted critical START time for an event, or null when no medicine in the event
     * is food-gap delayed. Uses the most food-restrictive medicine (latest eligible time).
     */
    private suspend fun foodAdjustedCriticalStart(
        repo: com.tbmedtrack.app.data.MedRepository,
        date: LocalDate,
        timeMinutes: Int,
        scheduledMillis: Long
    ): Long? {
        val foodRepo = ServiceLocator.foodRepository(context)
        val doses = repo.getDosesForDay(date).filter { it.timeMinutes == timeMinutes }
        var best: com.tbmedtrack.app.data.FoodTiming? = null
        for (dose in doses) {
            val med = repo.getMedicine(dose.medicineId) ?: continue
            val t = foodRepo.timingFor(med, scheduledMillis) ?: continue
            if (t.foodAdjusted && (best == null || t.eligibleMillis > best!!.eligibleMillis)) best = t
        }
        return best?.criticalStartMillis
    }

    /**
     * Schedule escalation [nextEscalation] at an EXPLICIT [triggerAt]. Used after a food-adjusted
     * critical alarm fires so the next step repeats from the actual fire time + interval rather
     * than being recomputed from the raw scheduled time.
     */
    fun scheduleNextEscalationAt(
        epochDay: Long,
        timeMinutes: Int,
        scheduledMillis: Long,
        nextEscalation: Int,
        triggerAt: Long
    ) {
        if (nextEscalation > maxEscalations) return
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
                val now = System.currentTimeMillis()

                // Food-gap awareness: if a recorded meal pushed this event's eligible time out,
                // the critical alarm must start from (foodEligible + grace), not the raw schedule.
                val foodCriticalStart = foodAdjustedCriticalStart(repo, date, t, scheduledMillis)

                if (foodCriticalStart != null) {
                    if (foodCriticalStart >= now) {
                        scheduleCriticalAt(date.toEpochDay(), t, scheduledMillis, foodCriticalStart)
                    } else {
                        // Food-adjusted critical time already passed: resume at the next interval step.
                        var trigger = foodCriticalStart
                        var next = 1
                        while (trigger < now && next <= maxEscalations) { trigger += intervalMillis; next++ }
                        if (next <= maxEscalations) {
                            scheduleNextEscalationAt(date.toEpochDay(), t, scheduledMillis, next, trigger)
                        }
                    }
                    continue
                }

                // No food adjustment: original behavior.
                if (scheduledMillis >= now) {
                    scheduleEventChain(date.toEpochDay(), t, scheduledMillis)
                } else {
                    var next = 1
                    while (next <= maxEscalations && triggerFor(scheduledMillis, next) < now) next++
                    if (next <= maxEscalations) {
                        scheduleNextEscalation(date.toEpochDay(), t, scheduledMillis, next)
                    }
                }
            }
        }
    }
}
