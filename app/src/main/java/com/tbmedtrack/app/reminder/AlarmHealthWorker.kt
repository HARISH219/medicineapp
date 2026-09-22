package com.tbmedtrack.app.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tbmedtrack.app.ServiceLocator

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
            NotificationHelper.ensureChannel(applicationContext)
            ServiceLocator.alarmScheduler(applicationContext).rescheduleAll()
            ServiceLocator.criticalAlarmScheduler(applicationContext).rescheduleTodayAndFuture()
            ServiceLocator.syncManager(applicationContext).syncNow()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
