package com.tbmedtrack.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tbmedtrack.app.data.RegimenSeeder
import com.tbmedtrack.app.reminder.AlarmHealthWorker
import com.tbmedtrack.app.reminder.NotificationHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TbMedApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)

        appScope.launch {
            runCatching {
                // Register this device (PRIMARY on first install).
                ServiceLocator.deviceRepository(this@TbMedApplication).ensureRegistered()
                // Seed the MDR-TB regimen on first launch (editable records).
                val repo = ServiceLocator.medRepository(this@TbMedApplication)
                val settings = ServiceLocator.settingsRepository(this@TbMedApplication)
                RegimenSeeder(repo, settings).seedIfNeeded()
                // Load the active tracking start so past unconfigured days are never "missed".
                repo.trackingStartDay = settings.settings.first().trackingStartDay
                // (Re)register reminders and critical escalation chains.
                ServiceLocator.alarmScheduler(this@TbMedApplication).rescheduleAll()
                ServiceLocator.criticalAlarmScheduler(this@TbMedApplication).rescheduleTodayAndFuture()
                // Drain any pending sync.
                ServiceLocator.syncManager(this@TbMedApplication).syncNow()
            }
        }

        scheduleAlarmHealthCheck()
    }

    /**
     * A periodic WorkManager job re-arms alarms if the OS or a manufacturer battery
     * optimization silently dropped them. This is a safety net, not a guarantee.
     */
    private fun scheduleAlarmHealthCheck() {
        val request = PeriodicWorkRequestBuilder<AlarmHealthWorker>(1, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "alarm_health_check",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
