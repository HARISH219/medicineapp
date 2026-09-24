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
            ReminderKeys.ACTION_FOOD_EARLY, ReminderKeys.ACTION_FOOD_ELIGIBLE -> {
                val epochDay = intent.getLongExtra(ReminderKeys.EXTRA_EPOCH_DAY, -1L)
                val timeMinutes = intent.getIntExtra(ReminderKeys.EXTRA_TIME_MINUTES, -1)
                val scheduledMillis = intent.getLongExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, -1L)
                val eligibleMillis = intent.getLongExtra(ReminderKeys.EXTRA_ELIGIBLE_MILLIS, -1L)
                val foodMillis = intent.getLongExtra(ReminderKeys.EXTRA_FOOD_MILLIS, -1L)
                if (epochDay < 0 || timeMinutes < 0) return
                val early = intent.action == ReminderKeys.ACTION_FOOD_EARLY
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        handleFood(context, epochDay, timeMinutes, scheduledMillis, eligibleMillis, foodMillis, early)
                    } finally { pending.finish() }
                }
            }
        }
    }

    /** 10-minute "coming up" reminder, or the "eligible now" notice. Reminder-only. */
    private suspend fun handleFood(
        context: Context,
        epochDay: Long,
        timeMinutes: Int,
        scheduledMillis: Long,
        eligibleMillis: Long,
        foodMillis: Long,
        early: Boolean
    ) {
        val repo = ServiceLocator.medRepository(context)
        // If already recorded, don't nag.
        if (!repo.isEventPending(epochDay, timeMinutes)) return
        val settings = runCatching {
            ServiceLocator.settingsRepository(context).settings.first()
        }.getOrNull()
        if (settings?.remindersEnabled == false) return

        val date = ScheduleUtil.dateFromEpochDay(epochDay)
        val names = repo.getDosesForDay(date)
            .filter { it.timeMinutes == timeMinutes }
            .map { it.medicineName }

        NotificationHelper.showFoodGapReminder(
            context = context,
            timeMinutes = timeMinutes,
            scheduledMillis = scheduledMillis,
            eligibleMillis = eligibleMillis,
            foodMillis = foodMillis,
            medicineNames = names,
            early = early,
            sound = settings?.soundEnabled ?: true,
            vibration = settings?.vibrationEnabled ?: true
        )
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

        // Schedule the next escalation one interval after THIS fire time. Using the actual fire
        // time keeps the cadence correct even when the chain started from a food-adjusted time.
        critical.refreshInterval()
        val nextTrigger = System.currentTimeMillis() + critical.intervalMillisValue()
        critical.scheduleNextEscalationAt(epochDay, timeMinutes, scheduledMillis, escalation + 1, nextTrigger)
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

        // Use generated doses (phase-aware) as the source of truth for whether THIS medicine is
        // actually scheduled today — so phased meds don't notify on non-scheduled days.
        val repo = ServiceLocator.medRepository(context)
        val dosesToday = repo.getDosesForDay(date)
        val thisScheduled = dosesToday.any { it.medicineId == med.id && it.timeMinutes == sch.timeMinutes }

        // Food-gap suppression: if a recorded meal pushed this medicine's eligible time later,
        // do NOT fire the normal reminder now. The food-gap chain (early + eligible) fires instead.
        val foodTiming = runCatching {
            ServiceLocator.foodRepository(context).timingFor(med, scheduledMillis)
        }.getOrNull()
        val foodBlocked = foodTiming?.foodAdjusted == true && foodTiming.isWaiting()

        if (med.active && sch.enabled && remindersEnabled && thisScheduled && !foodBlocked) {
            // Only notify if not already taken.
            val existing = db.logDao().findLog(med.id, scheduleId, scheduledMillis)
            if (existing?.status != DoseStatus.TAKEN) {
                // Count how many medicines are due at this same time today for a grouped message.
                val doses = dosesToday.filter { it.timeMinutes == sch.timeMinutes }
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
