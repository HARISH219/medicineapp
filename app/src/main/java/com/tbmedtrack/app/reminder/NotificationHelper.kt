package com.tbmedtrack.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tbmedtrack.app.MainActivity
import com.tbmedtrack.app.R

/**
 * Builds and posts medication reminder notifications with Mark-as-Taken and Snooze actions.
 * A dose event is identified by (timeMinutes, scheduledMillis); the notification id is derived
 * from the scheduled time so repeated fires update the same notification.
 */
object NotificationHelper {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(ReminderKeys.CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    ReminderKeys.CHANNEL_ID,
                    ReminderKeys.CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High-priority reminders for your scheduled medicines"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                    enableLights(true)
                    setShowBadge(true)
                    // Default notification sound so a due dose is audible when enabled.
                    val soundUri = android.media.RingtoneManager
                        .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(soundUri, attrs)
                }
                mgr.createNotificationChannel(channel)
            }
            if (mgr.getNotificationChannel(ReminderKeys.CRITICAL_CHANNEL_ID) == null) {
                val critical = NotificationChannel(
                    ReminderKeys.CRITICAL_CHANNEL_ID,
                    ReminderKeys.CRITICAL_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Escalating alarm when a required dose has not been recorded"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 300, 500, 300, 500)
                    setBypassDnd(true)
                    setShowBadge(true)
                    enableLights(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    // Use the alarm stream so the critical alert is loud where the OS permits it.
                    val alarmUri = android.media.RingtoneManager
                        .getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                        ?: android.media.RingtoneManager
                            .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    val attrs = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    setSound(alarmUri, attrs)
                }
                mgr.createNotificationChannel(critical)
            }
        }
    }

    fun notificationIdFor(scheduledMillis: Long): Int =
        (scheduledMillis / 60000L).toInt()

    fun showDoseReminder(
        context: Context,
        title: String,
        body: String,
        medicineId: Long,
        scheduleId: Long,
        scheduledMillis: Long,
        timeMinutes: Int,
        sound: Boolean,
        vibration: Boolean
    ) {
        ensureChannel(context)
        val notifId = notificationIdFor(scheduledMillis)

        val contentIntent = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val takenIntent = actionPending(
            context, ReminderKeys.ACTION_MARK_TAKEN, notifId,
            medicineId, scheduleId, scheduledMillis, timeMinutes, notifId
        )
        val snoozeIntent = actionPending(
            context, ReminderKeys.ACTION_SNOOZE, notifId + 100000,
            medicineId, scheduleId, scheduledMillis, timeMinutes, notifId
        )

        val builder = NotificationCompat.Builder(context, ReminderKeys.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_check, "Mark as Taken", takenIntent)
            .addAction(R.drawable.ic_snooze, "Snooze", snoozeIntent)

        if (!sound && !vibration) {
            builder.setSilent(true)
        } else {
            var defaults = 0
            if (sound) defaults = defaults or NotificationCompat.DEFAULT_SOUND
            if (vibration) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
            builder.setDefaults(defaults)
        }

        if (hasNotificationPermission(context)) {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        }
    }

    fun cancel(context: Context, scheduledMillis: Long) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(scheduledMillis))
    }

    fun cancelCritical(context: Context) {
        NotificationManagerCompat.from(context).cancel(ReminderKeys.CRITICAL_NOTIFICATION_ID)
    }

    /**
     * Show the escalating critical alert for a required morning dose. [escalation] 0 is the
     * first (10 AM) reminder; >=1 is critical escalation (11 AM onward). Uses a full-screen
     * intent so the red alert can appear over the lock screen where the OS permits it.
     */
    fun showCriticalDoseAlert(
        context: Context,
        epochDay: Long,
        timeMinutes: Int,
        scheduledMillis: Long,
        medicineNames: List<String>,
        escalation: Int,
        sound: Boolean,
        vibration: Boolean
    ) {
        ensureChannel(context)

        val fullScreenIntent = Intent(context, CriticalAlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ReminderKeys.EXTRA_EPOCH_DAY, epochDay)
            putExtra(ReminderKeys.EXTRA_TIME_MINUTES, timeMinutes)
            putExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, scheduledMillis)
            putExtra(ReminderKeys.EXTRA_ESCALATION, escalation)
        }
        val fullScreenPending = PendingIntent.getActivity(
            context, 900000 + timeMinutes, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val critical = escalation >= 1
        val title = if (critical) "🚨 MEDICATION ALERT" else "💊 Medicine time"
        val count = medicineNames.size
        val body = buildString {
            if (critical) {
                append("Your ")
                append(com.tbmedtrack.app.util.ScheduleUtil.formatTime(timeMinutes))
                append(" medication has not been recorded as taken.\n")
            } else {
                append("Today's TB medication combination is ready.\n")
            }
            append("Today's combination: $count ${if (count == 1) "medicine" else "medicines"}")
            if (medicineNames.isNotEmpty()) {
                append("\n")
                append(medicineNames.joinToString("\n") { "• $it" })
            }
        }

        val builder = NotificationCompat.Builder(context, ReminderKeys.CRITICAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle(title)
            .setContentText("Today's combination: $count medicines")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)              // cannot be casually swiped away
            .setAutoCancel(false)          // dismissing never means "taken"
            .setContentIntent(fullScreenPending)

        if (critical) {
            // Critical alert is WARNING-ONLY: no "Medicine Taken" action. Tapping opens the
            // full-screen alert; recording happens only on the normal medication screen.
            builder.setFullScreenIntent(fullScreenPending, true)
        } else {
            // The first (normal) reminder may offer a quick "Mark as taken" action.
            val takenIntent = Intent(context, NotificationActionReceiver::class.java).apply {
                action = ReminderKeys.ACTION_MARK_EVENT_TAKEN
                putExtra(ReminderKeys.EXTRA_EPOCH_DAY, epochDay)
                putExtra(ReminderKeys.EXTRA_TIME_MINUTES, timeMinutes)
                putExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, scheduledMillis)
                putExtra(ReminderKeys.EXTRA_NOTIFICATION_ID, ReminderKeys.CRITICAL_NOTIFICATION_ID)
            }
            val takenPending = PendingIntent.getBroadcast(
                context, 910000 + timeMinutes, takenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_check, "Mark as taken", takenPending)
        }
        if (sound || vibration) {
            var defaults = 0
            if (sound) defaults = defaults or NotificationCompat.DEFAULT_SOUND
            if (vibration) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
            builder.setDefaults(defaults)
        }

        if (hasNotificationPermission(context)) {
            NotificationManagerCompat.from(context)
                .notify(ReminderKeys.CRITICAL_NOTIFICATION_ID, builder.build())
        }
    }

    /**
     * Food → medicine gap reminder (reminder-only, never records anything).
     * [early] true = the 10-minute "coming up" reminder; false = the "eligible now" notice.
     * Explains the food delay: shows original schedule, food time, and earliest eligible time.
     */
    fun showFoodGapReminder(
        context: Context,
        timeMinutes: Int,
        scheduledMillis: Long,
        eligibleMillis: Long,
        foodMillis: Long,
        medicineNames: List<String>,
        early: Boolean,
        sound: Boolean,
        vibration: Boolean
    ) {
        ensureChannel(context)
        val notifId = notificationIdFor(scheduledMillis)

        val contentIntent = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun clock(m: Long) = com.tbmedtrack.app.util.ScheduleUtil
            .localDateTime(m).toLocalTime()
            .let { com.tbmedtrack.app.util.ScheduleUtil.formatTime(it.hour * 60 + it.minute) }

        val title = if (early) "💊 Medicine coming up" else "💊 Medicine time"
        val lead = if (early)
            "Your medication can be taken in 10 minutes."
        else
            "Your medication can now be taken. Food gap complete ✓"
        val body = buildString {
            append(lead).append("\n")
            append("Scheduled: ${com.tbmedtrack.app.util.ScheduleUtil.formatTime(timeMinutes)}\n")
            if (foodMillis > 0) append("Food recorded: ${clock(foodMillis)}\n")
            append("Earliest medication time: ${clock(eligibleMillis)}")
            if (medicineNames.isNotEmpty()) {
                append("\n")
                append(medicineNames.joinToString("\n") { "• $it" })
            }
        }

        val builder = NotificationCompat.Builder(context, ReminderKeys.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pill)
            .setContentTitle(title)
            .setContentText(lead)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)

        if (!sound && !vibration) {
            builder.setSilent(true)
        } else {
            var defaults = 0
            if (sound) defaults = defaults or NotificationCompat.DEFAULT_SOUND
            if (vibration) defaults = defaults or NotificationCompat.DEFAULT_VIBRATE
            builder.setDefaults(defaults)
        }

        if (hasNotificationPermission(context)) {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        }
    }

    private fun actionPending(
        context: Context,
        action: String,
        requestCode: Int,
        medicineId: Long,
        scheduleId: Long,
        scheduledMillis: Long,
        timeMinutes: Int,
        notificationId: Int
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderKeys.EXTRA_MEDICINE_ID, medicineId)
            putExtra(ReminderKeys.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(ReminderKeys.EXTRA_SCHEDULED_MILLIS, scheduledMillis)
            putExtra(ReminderKeys.EXTRA_TIME_MINUTES, timeMinutes)
            putExtra(ReminderKeys.EXTRA_NOTIFICATION_ID, notificationId)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }
}
