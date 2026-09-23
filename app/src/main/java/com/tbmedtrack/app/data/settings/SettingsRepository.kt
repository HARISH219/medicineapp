package com.tbmedtrack.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tbmedtrack_settings")

enum class ThemeMode { LIGHT, DARK, SYSTEM }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val remindersEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val defaultSnoozeMinutes: Int = 10,
    val weekStartsMonday: Boolean = true,
    val onboardingDone: Boolean = false,
    /** epoch day the user's treatment tracking started; 0 = not set (auto from first medicine) */
    val treatmentStartDay: Long = 0L,
    /** bedtime / night-medicine time in minutes since midnight */
    val nightMedicineMinutes: Int = 22 * 60,
    /** whether the seeded MDR-TB regimen has been created */
    val regimenSeeded: Boolean = false,
    /** epoch day active reminders begin; unlogged doses before this are never "missed". 0=all tracked */
    val trackingStartDay: Long = 0L,
    /** repeat interval in minutes for critical reminders (default 60) */
    val escalationIntervalMinutes: Int = 60,
    /** delay in minutes after the scheduled time before the first critical alarm (default 60) */
    val criticalStartDelayMinutes: Int = 60,
    /** minutes-since-midnight of the primary morning dose (default 10:00) */
    val morningDoseMinutes: Int = 10 * 60,
    /** whether monitor devices receive each kind of alert */
    val notifyMonitorNotRecorded: Boolean = true,
    val notifyMonitorTaken: Boolean = true,
    val notifyMonitorCritical: Boolean = true,
    /** accessibility: disable the flashing/pulsing critical animation */
    val reduceMotion: Boolean = false
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val REMINDERS = booleanPreferencesKey("reminders_enabled")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val VIBRATION = booleanPreferencesKey("vibration_enabled")
        val SNOOZE = intPreferencesKey("default_snooze")
        val WEEK_MONDAY = booleanPreferencesKey("week_monday")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val TREATMENT_START = longPreferencesKey("treatment_start_day")
        val NIGHT_MED = intPreferencesKey("night_med_minutes")
        val REGIMEN_SEEDED = booleanPreferencesKey("regimen_seeded")
        val TRACKING_START = longPreferencesKey("tracking_start_day")
        val ESCALATION_INTERVAL = intPreferencesKey("escalation_interval")
        val CRITICAL_START_DELAY = intPreferencesKey("critical_start_delay")
        val MORNING_DOSE = intPreferencesKey("morning_dose_minutes")
        val NOTIFY_NOT_RECORDED = booleanPreferencesKey("notify_not_recorded")
        val NOTIFY_TAKEN = booleanPreferencesKey("notify_taken")
        val NOTIFY_CRITICAL = booleanPreferencesKey("notify_critical")
        val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "SYSTEM") }
                .getOrDefault(ThemeMode.SYSTEM),
            remindersEnabled = p[Keys.REMINDERS] ?: true,
            soundEnabled = p[Keys.SOUND] ?: true,
            vibrationEnabled = p[Keys.VIBRATION] ?: true,
            defaultSnoozeMinutes = p[Keys.SNOOZE] ?: 10,
            weekStartsMonday = p[Keys.WEEK_MONDAY] ?: true,
            onboardingDone = p[Keys.ONBOARDING] ?: false,
            treatmentStartDay = p[Keys.TREATMENT_START] ?: 0L,
            nightMedicineMinutes = p[Keys.NIGHT_MED] ?: (22 * 60),
            regimenSeeded = p[Keys.REGIMEN_SEEDED] ?: false,
            trackingStartDay = p[Keys.TRACKING_START] ?: 0L,
            escalationIntervalMinutes = p[Keys.ESCALATION_INTERVAL] ?: 60,
            criticalStartDelayMinutes = p[Keys.CRITICAL_START_DELAY] ?: 60,
            morningDoseMinutes = p[Keys.MORNING_DOSE] ?: (10 * 60),
            notifyMonitorNotRecorded = p[Keys.NOTIFY_NOT_RECORDED] ?: true,
            notifyMonitorTaken = p[Keys.NOTIFY_TAKEN] ?: true,
            notifyMonitorCritical = p[Keys.NOTIFY_CRITICAL] ?: true,
            reduceMotion = p[Keys.REDUCE_MOTION] ?: false
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.dataStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setReminders(enabled: Boolean) = context.dataStore.edit { it[Keys.REMINDERS] = enabled }
    suspend fun setSound(enabled: Boolean) = context.dataStore.edit { it[Keys.SOUND] = enabled }
    suspend fun setVibration(enabled: Boolean) = context.dataStore.edit { it[Keys.VIBRATION] = enabled }
    suspend fun setSnooze(minutes: Int) = context.dataStore.edit { it[Keys.SNOOZE] = minutes }
    suspend fun setWeekStartsMonday(monday: Boolean) = context.dataStore.edit { it[Keys.WEEK_MONDAY] = monday }
    suspend fun setOnboardingDone(done: Boolean) = context.dataStore.edit { it[Keys.ONBOARDING] = done }
    suspend fun setTreatmentStartDay(day: Long) = context.dataStore.edit { it[Keys.TREATMENT_START] = day }
    suspend fun setNightMedicineMinutes(m: Int) = context.dataStore.edit { it[Keys.NIGHT_MED] = m }
    suspend fun setRegimenSeeded(seeded: Boolean) = context.dataStore.edit { it[Keys.REGIMEN_SEEDED] = seeded }
    suspend fun setTrackingStartDay(day: Long) = context.dataStore.edit { it[Keys.TRACKING_START] = day }
    suspend fun setEscalationIntervalMinutes(m: Int) = context.dataStore.edit { it[Keys.ESCALATION_INTERVAL] = m }
    suspend fun setCriticalStartDelayMinutes(m: Int) = context.dataStore.edit { it[Keys.CRITICAL_START_DELAY] = m }
    suspend fun setMorningDoseMinutes(m: Int) = context.dataStore.edit { it[Keys.MORNING_DOSE] = m }
    suspend fun setNotifyNotRecorded(v: Boolean) = context.dataStore.edit { it[Keys.NOTIFY_NOT_RECORDED] = v }
    suspend fun setNotifyTaken(v: Boolean) = context.dataStore.edit { it[Keys.NOTIFY_TAKEN] = v }
    suspend fun setNotifyCritical(v: Boolean) = context.dataStore.edit { it[Keys.NOTIFY_CRITICAL] = v }
    suspend fun setReduceMotion(v: Boolean) = context.dataStore.edit { it[Keys.REDUCE_MOTION] = v }
}
