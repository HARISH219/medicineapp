package com.tbmedtrack.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.settings.DeviceRoleValue
import kotlinx.coroutines.flow.first

/**
 * Durable fallback for monitor updates when the app process is backgrounded or stopped.
 * WorkManager periodic work has a 15-minute platform minimum; foreground monitoring still
 * checks every 10 minutes and immediately on resume.
 */
class MonitorSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val settings = ServiceLocator.settingsRepository(applicationContext).settings.first()
        if (!settings.setupWizardDone || settings.deviceRole != DeviceRoleValue.SECONDARY) {
            return@runCatching Result.success()
        }
        val sync = ServiceLocator.syncManager(applicationContext)
        sync.reconfigure()
        sync.syncNow()
        if (sync.status.value.phase == SyncPhase.ERROR) Result.retry() else Result.success()
    }.getOrElse { Result.retry() }
}
