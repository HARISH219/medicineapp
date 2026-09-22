package com.tbmedtrack.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.tbmedtrack.app.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Handles the Mark-as-Taken and Snooze actions from a reminder notification.
 * Marking taken records every medicine due at that time; snooze reschedules and logs.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val scheduledMillis = intent.getLongExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, -1L)
        val timeMinutes = intent.getIntExtra(ReminderKeys.EXTRA_TIME_MINUTES, -1)
        val epochDay = intent.getLongExtra(ReminderKeys.EXTRA_EPOCH_DAY, -1L)
        val medicineId = intent.getLongExtra(ReminderKeys.EXTRA_MEDICINE_ID, -1L)
        val scheduleId = intent.getLongExtra(ReminderKeys.EXTRA_SCHEDULE_ID, -1L)
        val notificationId = intent.getIntExtra(ReminderKeys.EXTRA_NOTIFICATION_ID, -1)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = ServiceLocator.medRepository(context)
                when (action) {
                    ReminderKeys.ACTION_MARK_EVENT_TAKEN -> {
                        if (timeMinutes < 0 || scheduledMillis < 0) return@launch
                        val day = if (epochDay >= 0) epochDay else com.tbmedtrack.app.util.ScheduleUtil
                            .localDateTime(scheduledMillis).toLocalDate().toEpochDay()
                        repo.markEventTaken(day, timeMinutes)
                        ServiceLocator.criticalAlarmScheduler(context)
                            .cancelEventChain(day, timeMinutes, scheduledMillis)
                        ServiceLocator.syncManager(context).queue()
                    }
                    ReminderKeys.ACTION_MARK_TAKEN -> {
                        if (scheduledMillis < 0) return@launch
                        val day = com.tbmedtrack.app.util.ScheduleUtil
                            .localDateTime(scheduledMillis).toLocalDate().toEpochDay()
                        if (timeMinutes >= 0) {
                            repo.markEventTaken(day, timeMinutes)
                            ServiceLocator.criticalAlarmScheduler(context)
                                .cancelEventChain(day, timeMinutes, scheduledMillis)
                        } else if (medicineId >= 0 && scheduleId >= 0) {
                            repo.markTakenByIds(medicineId, scheduleId, scheduledMillis)
                        }
                        NotificationHelper.cancel(context, scheduledMillis)
                        ServiceLocator.syncManager(context).queue()
                    }
                    ReminderKeys.ACTION_SNOOZE -> {
                        if (medicineId < 0 || scheduleId < 0 || scheduledMillis < 0) return@launch
                        val settings = ServiceLocator.settingsRepository(context).settings.first()
                        val snoozeMin = settings.defaultSnoozeMinutes.coerceAtLeast(1)
                        repo.recordSnooze(medicineId, scheduleId, scheduledMillis)
                        val trigger = System.currentTimeMillis() + snoozeMin * 60_000L
                        scheduleSnoozeFire(context, scheduleId, scheduledMillis, trigger)
                        NotificationHelper.cancel(context, scheduledMillis)
                    }
                }
            } finally {
                if (notificationId >= 0) {
                    NotificationManagerCompat.from(context).cancel(notificationId)
                }
                pending.finish()
            }
        }
    }

    private fun scheduleSnoozeFire(
        context: Context,
        scheduleId: Long,
        scheduledMillis: Long,
        triggerAt: Long
    ) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val fire = Intent(context, AlarmReceiver::class.java).apply {
            this.action = ReminderKeys.ACTION_FIRE
            putExtra(ReminderKeys.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, scheduledMillis)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context,
            (scheduleId * 31 + (scheduledMillis / 60000L)).toInt(),
            fire,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } catch (se: SecurityException) {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }
}
