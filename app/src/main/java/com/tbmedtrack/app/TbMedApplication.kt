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
                val appSettings = settings.settings.first()
                repo.trackingStartDay = appSettings.trackingStartDay
                val isSecondary = appSettings.deviceRole ==
                    com.tbmedtrack.app.data.settings.DeviceRoleValue.SECONDARY
                // A SECONDARY (monitoring) device must NOT schedule normal reminders, food
                // reminders, or its own critical escalation — it only monitors synced state.
                if (!isSecondary) {
                    ServiceLocator.alarmScheduler(this@TbMedApplication).rescheduleAll()
                    ServiceLocator.criticalAlarmScheduler(this@TbMedApplication).rescheduleTodayAndFuture()
                    ServiceLocator.foodGapScheduler(this@TbMedApplication).rescheduleForToday()
                }
                // Both roles sync (the secondary pulls status; the main pushes events).
                ServiceLocator.syncManager(this@TbMedApplication).reconfigure()
                ServiceLocator.syncManager(this@TbMedApplication).syncNow()
                // Refresh any placed home-screen widgets with current status.
                com.tbmedtrack.app.widget.MedTrackWidgetProvider.updateAllWidgets(this@TbMedApplication)
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
