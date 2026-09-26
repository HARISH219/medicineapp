package com.tbmedtrack.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tbmedtrack.app.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-registers all future medication reminders after device reboot, app update,
 * or a system time / timezone change.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        NotificationHelper.ensureChannel(context)
                        ServiceLocator.deviceRepository(context).ensureRegistered()
                        val settings = ServiceLocator.settingsRepository(context).settings.first()
                        if (settings.setupWizardDone &&
                            settings.deviceRole == com.tbmedtrack.app.data.settings.DeviceRoleValue.MAIN
                        ) {
                            ServiceLocator.alarmScheduler(context).rescheduleAll()
                            ServiceLocator.criticalAlarmScheduler(context).rescheduleTodayAndFuture()
                            ServiceLocator.foodGapScheduler(context).rescheduleForToday()
                        } else {
                            // Monitoring devices are read-only and never own patient alarms.
                            ServiceLocator.syncManager(context).reconfigure()
                            ServiceLocator.syncManager(context).syncNow()
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
