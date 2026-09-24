package com.tbmedtrack.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.util.ScheduleUtil

/**
 * Schedules the food → medicine gap alarm chain for today's remaining food-gapped dose events:
 *   - a 10-minute "coming up" reminder at (eligible − 10 min)
 *   - an "eligible now" notice at the eligible time
 *   - the critical escalation, re-armed from (eligible + grace) instead of the raw schedule.
 *
 * Called when the user records food (recompute for the latest meal) and on boot/app start.
 * Fully local — no network required.
 */
class FoodGapScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms() else true

    private fun reqCode(action: String, timeMinutes: Int): Int {
        val base = when (action) {
            ReminderKeys.ACTION_FOOD_EARLY -> 700000
            ReminderKeys.ACTION_FOOD_ELIGIBLE -> 720000
            else -> 740000
        }
        return base + timeMinutes
    }

    private fun pending(
        action: String,
        epochDay: Long,
        timeMinutes: Int,
        scheduledMillis: Long,
        eligibleMillis: Long,
        foodMillis: Long,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderKeys.EXTRA_EPOCH_DAY, epochDay)
            putExtra(ReminderKeys.EXTRA_TIME_MINUTES, timeMinutes)
            putExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, scheduledMillis)
            putExtra(ReminderKeys.EXTRA_ELIGIBLE_MILLIS, eligibleMillis)
            putExtra(ReminderKeys.EXTRA_FOOD_MILLIS, foodMillis)
        }
        return PendingIntent.getBroadcast(context, reqCode(action, timeMinutes), intent, flags)
    }

    private fun setExact(pi: PendingIntent, triggerAt: Long) {
        try {
            if (canScheduleExact()) alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            else alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (se: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    /**
     * Recompute and (re)schedule the food-gap chain for every pending, food-gapped dose event
     * today. Also cancels any existing normal reminder for those events and re-arms the critical
     * chain from the food-adjusted critical start time.
     */
    suspend fun rescheduleForToday() {
        val repo = ServiceLocator.medRepository(context)
        val foodRepo = ServiceLocator.foodRepository(context)
        val critical = ServiceLocator.criticalAlarmScheduler(context)
        critical.refreshInterval()

        val today = ScheduleUtil.today()
        val epochDay = today.toEpochDay()
        val now = System.currentTimeMillis()
        val doses = repo.getDosesForDay(today)

        // Group by clock time so each event (all meds at a time) is handled once.
        val byTime = doses.groupBy { it.timeMinutes }
        for ((timeMinutes, list) in byTime) {
            if (!repo.isEventPending(epochDay, timeMinutes)) {
                cancelChain(epochDay, timeMinutes)
                continue
            }
            val scheduledMillis = list.first().scheduledMillis
            // Use the most food-restrictive medicine in this event (largest gap / latest eligible).
            var best: com.tbmedtrack.app.data.FoodTiming? = null
            for (dose in list) {
                val med = repo.getMedicine(dose.medicineId) ?: continue
                val t = foodRepo.timingFor(med, scheduledMillis) ?: continue
                if (best == null || t.eligibleMillis > best!!.eligibleMillis) best = t
            }
            val timing = best
            if (timing == null || !timing.foodAdjusted) {
                // No food adjustment for this event: leave the normal chain in place.
                cancelChain(epochDay, timeMinutes)
                continue
            }
            val foodMillis = timing.foodTimeMillis ?: continue

            // Suppress the normal reminder while food-blocked; the food chain replaces it.
            NotificationHelper.cancel(context, scheduledMillis)

            // Early reminder (eligible - 10). Only if still in the future.
            if (timing.earlyReminderMillis > now) {
                pending(
                    ReminderKeys.ACTION_FOOD_EARLY, epochDay, timeMinutes, scheduledMillis,
                    timing.eligibleMillis, foodMillis,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )?.let { setExact(it, timing.earlyReminderMillis) }
            }
            // Eligible notice at the eligible time.
            if (timing.eligibleMillis > now) {
                pending(
                    ReminderKeys.ACTION_FOOD_ELIGIBLE, epochDay, timeMinutes, scheduledMillis,
                    timing.eligibleMillis, foodMillis,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )?.let { setExact(it, timing.eligibleMillis) }
            }
            // Re-arm the critical chain from the food-adjusted critical start (eligible + grace).
            critical.cancelEventChain(epochDay, timeMinutes, scheduledMillis)
            critical.scheduleCriticalAt(epochDay, timeMinutes, scheduledMillis, timing.criticalStartMillis)
        }
    }

    fun cancelChain(epochDay: Long, timeMinutes: Int) {
        listOf(ReminderKeys.ACTION_FOOD_EARLY, ReminderKeys.ACTION_FOOD_ELIGIBLE).forEach { action ->
            pending(
                action, epochDay, timeMinutes, 0L, 0L, 0L,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { alarmManager.cancel(it) }
        }
    }
}
