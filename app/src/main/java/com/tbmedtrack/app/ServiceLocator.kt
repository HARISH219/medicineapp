package com.tbmedtrack.app

import android.content.Context
import com.tbmedtrack.app.data.DeviceRepository
import com.tbmedtrack.app.data.MedRepository
import com.tbmedtrack.app.data.db.AppDatabase
import com.tbmedtrack.app.data.settings.SettingsRepository
import com.tbmedtrack.app.reminder.AlarmScheduler
import com.tbmedtrack.app.reminder.CriticalAlarmScheduler
import com.tbmedtrack.app.sync.SyncManager

/** Simple manual dependency container (no DI framework needed). */
object ServiceLocator {

    @Volatile private var medRepo: MedRepository? = null
    @Volatile private var settingsRepo: SettingsRepository? = null
    @Volatile private var scheduler: AlarmScheduler? = null
    @Volatile private var criticalScheduler: CriticalAlarmScheduler? = null
    @Volatile private var syncManager: SyncManager? = null
    @Volatile private var deviceRepo: DeviceRepository? = null

    fun deviceRepository(context: Context): DeviceRepository =
        deviceRepo ?: synchronized(this) {
            deviceRepo ?: DeviceRepository(context.applicationContext).also { deviceRepo = it }
        }

    fun medRepository(context: Context): MedRepository =
        medRepo ?: synchronized(this) {
            medRepo ?: run {
                val db = AppDatabase.get(context)
                val repo = MedRepository(db.medicineDao(), db.logDao(), db.auditDao(), db.syncOperationDao())
                repo.currentDeviceId = deviceRepository(context).deviceId
                repo.onEventRecorded = { syncManager(context).queue() }
                // trackingStartDay is loaded asynchronously by the Application on start; until
                // then it defaults to 0 (treat all scheduled days as tracked).
                medRepo = repo
                repo
            }
        }

    fun settingsRepository(context: Context): SettingsRepository =
        settingsRepo ?: synchronized(this) {
            settingsRepo ?: SettingsRepository(context.applicationContext).also { settingsRepo = it }
        }

    fun alarmScheduler(context: Context): AlarmScheduler =
        scheduler ?: synchronized(this) {
            scheduler ?: AlarmScheduler(context.applicationContext).also { scheduler = it }
        }

    fun criticalAlarmScheduler(context: Context): CriticalAlarmScheduler =
        criticalScheduler ?: synchronized(this) {
            criticalScheduler ?: CriticalAlarmScheduler(context.applicationContext).also { criticalScheduler = it }
        }

    fun syncManager(context: Context): SyncManager =
        syncManager ?: synchronized(this) {
            syncManager ?: SyncManager(context.applicationContext).also { syncManager = it }
        }
}
