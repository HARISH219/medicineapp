package com.tbmedtrack.app.util

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Helpers for the critical-reminder related permissions and their settings intents. */
object PermissionUtil {

    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
        } else true
    }

    /** Intent to the system "Alarms & reminders" screen (Android 12+). */
    fun exactAlarmSettingsIntent(context: Context): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else null
    }

    /** Intent to the app's notification settings screen. */
    fun notificationSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
    }

    /** Intent to battery optimization settings so the user can exempt the app. */
    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** True if the POST_NOTIFICATIONS runtime permission is granted (always true pre-Android 13). */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    /** True if the app is exempt from battery optimization (more reliable background alarms). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * True if the app can post full-screen intents (Android 14+ gates this behind a special
     * permission; pre-14 it is allowed by the manifest declaration).
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
        } else true
    }

    /** A single readiness flag: are the essential alert permissions in place? */
    fun alertsReady(context: Context): Boolean =
        hasNotificationPermission(context) && canScheduleExactAlarms(context)

    /** A user-facing item in the alert-permissions checklist. */
    data class PermissionItem(
        val label: String,
        val granted: Boolean,
        val actionLabel: String,
        val intent: Intent?
    )

    /**
     * The full alert-permissions checklist for Settings/onboarding. Only reports real OS state;
     * it never claims the app can override silent mode / DND / manufacturer limits.
     */
    fun alertChecklist(context: Context): List<PermissionItem> = buildList {
        add(
            PermissionItem(
                "Notifications enabled",
                hasNotificationPermission(context),
                "Open notification settings",
                notificationSettingsIntent(context)
            )
        )
        add(
            PermissionItem(
                "Exact alarms allowed",
                canScheduleExactAlarms(context),
                "Enable exact alarms",
                exactAlarmSettingsIntent(context)
            )
        )
        add(
            PermissionItem(
                "Full-screen alerts allowed",
                canUseFullScreenIntent(context),
                "Open notification settings",
                notificationSettingsIntent(context)
            )
        )
        add(
            PermissionItem(
                "Battery optimization exemption",
                isIgnoringBatteryOptimizations(context),
                "Battery optimization settings",
                batteryOptimizationSettingsIntent()
            )
        )
    }
}
