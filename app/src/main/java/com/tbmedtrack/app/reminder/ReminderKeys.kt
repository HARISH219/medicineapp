package com.tbmedtrack.app.reminder

/** Shared intent extras / action constants for the reminder subsystem. */
object ReminderKeys {
    const val ACTION_FIRE = "com.tbmedtrack.app.ACTION_FIRE"
    const val ACTION_MARK_TAKEN = "com.tbmedtrack.app.ACTION_MARK_TAKEN"
    const val ACTION_SNOOZE = "com.tbmedtrack.app.ACTION_SNOOZE"

    /** Escalation alarm for a required dose event (10 AM first, then hourly). */
    const val ACTION_CRITICAL = "com.tbmedtrack.app.ACTION_CRITICAL"
    /** Mark an entire dose event (all medicines at a time) taken. */
    const val ACTION_MARK_EVENT_TAKEN = "com.tbmedtrack.app.ACTION_MARK_EVENT_TAKEN"

    const val EXTRA_MEDICINE_ID = "medicineId"
    const val EXTRA_SCHEDULE_ID = "scheduleId"
    const val EXTRA_SCHEDULED_MILLIS = "scheduledMillis"
    const val EXTRA_TIME_MINUTES = "timeMinutes"
    const val EXTRA_EPOCH_DAY = "epochDay"
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    /** number of escalation steps elapsed for the event (0 = first 10AM reminder) */
    const val EXTRA_ESCALATION = "escalation"

    // NOTE: channel importance/sound is fixed once a channel is created by Android. Bumping the
    // id suffix (_v2) forces a fresh HIGH-importance "Medication Alerts" channel with sound.
    const val CHANNEL_ID = "medication_alerts_v2"
    const val CHANNEL_NAME = "Medication Alerts"

    const val CRITICAL_CHANNEL_ID = "critical_medication_alarm_v2"
    const val CRITICAL_CHANNEL_NAME = "Critical Medication Alarm"

    /** notification id used for the single active critical morning alert */
    const val CRITICAL_NOTIFICATION_ID = 424242
}
