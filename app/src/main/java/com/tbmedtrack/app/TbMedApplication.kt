package com.tbmedtrack.app

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.tbmedtrack.app.data.RegimenSeeder
import com.tbmedtrack.app.reminder.AlarmHealthWorker
import com.tbmedtrack.app.reminder.NotificationHelper
import com.tbmedtrack.app.sync.MonitorSyncWorker
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
                val repo = ServiceLocator.medRepository(this@TbMedApplication)
                val settings = ServiceLocator.settingsRepository(this@TbMedApplication)
                val appSettings = settings.settings.first()
                val isPrimaryReady = appSettings.setupWizardDone && appSettings.deviceRole ==
                    com.tbmedtrack.app.data.settings.DeviceRoleValue.MAIN
                // Never seed an independent regimen on a monitoring or undecided device.
                if (isPrimaryReady) RegimenSeeder(repo, settings).seedIfNeeded()
                // Load the active tracking start so past unconfigured days are never "missed".
                repo.trackingStartDay = appSettings.trackingStartDay
                // Only a completed MAIN setup owns reminders and the alarm-health worker.
                if (isPrimaryReady) {
                    ServiceLocator.alarmScheduler(this@TbMedApplication).rescheduleAll()
                    ServiceLocator.criticalAlarmScheduler(this@TbMedApplication).rescheduleTodayAndFuture()
                    ServiceLocator.foodGapScheduler(this@TbMedApplication).rescheduleForToday()
                    scheduleAlarmHealthCheck()
                    WorkManager.getInstance(this@TbMedApplication)
                        .cancelUniqueWork("monitor_sync")
                } else {
                    WorkManager.getInstance(this@TbMedApplication)
                        .cancelUniqueWork("alarm_health_check")
                    val isMonitorReady = appSettings.setupWizardDone && appSettings.deviceRole ==
                        com.tbmedtrack.app.data.settings.DeviceRoleValue.SECONDARY
                    if (isMonitorReady) scheduleMonitorSync()
                    else WorkManager.getInstance(this@TbMedApplication).cancelUniqueWork("monitor_sync")
                }
                // Both roles sync (the secondary pulls status; the main pushes events).
                ServiceLocator.syncManager(this@TbMedApplication).reconfigure()
                ServiceLocator.syncManager(this@TbMedApplication).syncNow()
                // Refresh any placed home-screen widgets with current status.
                com.tbmedtrack.app.widget.MedTrackWidgetProvider.updateAllWidgets(this@TbMedApplication)
            }
        }
    }

    private fun scheduleMonitorSync() {
        val request = PeriodicWorkRequestBuilder<MonitorSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "monitor_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
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
