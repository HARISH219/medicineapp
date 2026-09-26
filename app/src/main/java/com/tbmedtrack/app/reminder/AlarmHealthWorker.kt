package com.tbmedtrack.app.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tbmedtrack.app.ServiceLocator
import kotlinx.coroutines.flow.first

/**
 * Periodic safety-net worker: re-registers reminders and critical escalation chains in
 * case the OS or a manufacturer battery optimization dropped the exact alarms. It also
 * drains the sync outbox opportunistically.
 */
class AlarmHealthWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val settings = ServiceLocator.settingsRepository(applicationContext).settings.first()
            if (!settings.setupWizardDone ||
                settings.deviceRole != com.tbmedtrack.app.data.settings.DeviceRoleValue.MAIN
            ) {
                // A monitor/undecided setup never re-arms patient reminder/critical alarms.
                if (settings.deviceRole == com.tbmedtrack.app.data.settings.DeviceRoleValue.SECONDARY) {
                    ServiceLocator.syncManager(applicationContext).syncNow()
                }
                return@runCatching Result.success()
            }
            NotificationHelper.ensureChannel(applicationContext)
            ServiceLocator.alarmScheduler(applicationContext).rescheduleAll()
            ServiceLocator.criticalAlarmScheduler(applicationContext).rescheduleTodayAndFuture()
            ServiceLocator.syncManager(applicationContext).syncNow()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
