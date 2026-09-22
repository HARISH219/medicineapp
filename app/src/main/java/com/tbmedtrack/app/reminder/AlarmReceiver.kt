package com.tbmedtrack.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.AppDatabase
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fired at a scheduled dose time. Posts the reminder notification for every medicine
 * that shares this time on this day, then schedules the next occurrence of the schedule.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ReminderKeys.ACTION_FIRE -> {
                val scheduleId = intent.getLongExtra(ReminderKeys.EXTRA_SCHEDULE_ID, -1L)
                val scheduledMillis = intent.getLongExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, -1L)
                if (scheduleId < 0 || scheduledMillis < 0) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { handle(context, scheduleId, scheduledMillis) } finally { pending.finish() }
                }
            }
            ReminderKeys.ACTION_CRITICAL -> {
                val epochDay = intent.getLongExtra(ReminderKeys.EXTRA_EPOCH_DAY, -1L)
                val timeMinutes = intent.getIntExtra(ReminderKeys.EXTRA_TIME_MINUTES, -1)
                val scheduledMillis = intent.getLongExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, -1L)
                val escalation = intent.getIntExtra(ReminderKeys.EXTRA_ESCALATION, 0)
                if (epochDay < 0 || timeMinutes < 0 || scheduledMillis < 0) return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try { handleCritical(context, epochDay, timeMinutes, scheduledMillis, escalation) }
                    finally { pending.finish() }
                }
            }
        }
    }

    private suspend fun handleCritical(
        context: Context,
        epochDay: Long,
        timeMinutes: Int,
        scheduledMillis: Long,
        escalation: Int
    ) {
        val repo = ServiceLocator.medRepository(context)
        val critical = ServiceLocator.criticalAlarmScheduler(context)

        // If already taken, stop the chain and clear any alert. Dismissal never reaches here.
        if (!repo.isEventPending(epochDay, timeMinutes)) {
            critical.cancelEventChain(epochDay, timeMinutes, scheduledMillis)
            return
        }

        val settings = runCatching {
            ServiceLocator.settingsRepository(context).settings.first()
        }.getOrNull()
        if (settings?.remindersEnabled == false) return

        val date = com.tbmedtrack.app.util.ScheduleUtil.localDateTime(scheduledMillis).toLocalDate()
        val names = repo.getDosesForDay(date)
            .filter { it.timeMinutes == timeMinutes }
            .map { it.medicineName }

        NotificationHelper.showCriticalDoseAlert(
            context = context,
            epochDay = epochDay,
            timeMinutes = timeMinutes,
            scheduledMillis = scheduledMillis,
            medicineNames = names,
            escalation = escalation,
            sound = settings?.soundEnabled ?: true,
            vibration = settings?.vibrationEnabled ?: true
        )
        // Notify monitor devices via the sync layer (real push happens through the backend).
        ServiceLocator.syncManager(context).queue()

        // Schedule the next hourly escalation.
        critical.scheduleNextEscalation(epochDay, timeMinutes, scheduledMillis, escalation + 1)
    }

    private suspend fun handle(context: Context, scheduleId: Long, scheduledMillis: Long) {
        val db = AppDatabase.get(context)
        val sch = db.medicineDao().getSchedule(scheduleId) ?: return
        val med = db.medicineDao().getMedicine(sch.medicineId) ?: return
        val appSettings = runCatching {
            ServiceLocator.settingsRepository(context).settings.first()
        }.getOrNull()

        val remindersEnabled = appSettings?.remindersEnabled ?: true
        val date = ScheduleUtil.localDateTime(scheduledMillis).toLocalDate()

        if (med.active && sch.enabled && remindersEnabled &&
            ScheduleUtil.appliesOn(med, sch, date)
        ) {
            // Only notify if not already taken.
            val existing = db.logDao().findLog(med.id, scheduleId, scheduledMillis)
            if (existing?.status != DoseStatus.TAKEN) {
                // Count how many medicines are due at this same time today for a grouped message.
                val repo = ServiceLocator.medRepository(context)
                val doses = repo.getDosesForDay(date).filter { it.timeMinutes == sch.timeMinutes }
                val count = doses.size
                val title = "💊 Medication reminder"
                val body = if (count > 1) {
                    "Your $count scheduled TB medicines are due now (${ScheduleUtil.formatTime(sch.timeMinutes)})."
                } else {
                    "${med.name} (${med.dose} ${med.unit}) is due now."
                }
                NotificationHelper.showDoseReminder(
                    context = context,
                    title = title,
                    body = body,
                    medicineId = med.id,
                    scheduleId = scheduleId,
                    scheduledMillis = scheduledMillis,
                    timeMinutes = sch.timeMinutes,
                    sound = appSettings?.soundEnabled ?: true,
                    vibration = appSettings?.vibrationEnabled ?: true
                )
            }
        }

        // Schedule the next occurrence (starting just after this one).
        val scheduler = ServiceLocator.alarmScheduler(context)
        scheduler.scheduleNextFor(med.id, scheduleId, scheduledMillis + 60_000L)
    }
}
