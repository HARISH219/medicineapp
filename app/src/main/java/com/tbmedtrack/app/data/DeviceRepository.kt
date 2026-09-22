package com.tbmedtrack.app.data

import android.content.Context
import com.tbmedtrack.app.data.db.AppDatabase
import com.tbmedtrack.app.data.db.Device
import com.tbmedtrack.app.data.db.DeviceRole
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Manages device identity and the local list of authorized devices. The stable device id
 * is generated once and persisted. On a fresh install this device is registered as PRIMARY;
 * a device that redeems an auth code becomes a MONITOR (handled by the sync layer/backend).
 */
class DeviceRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("tbmedtrack_device", Context.MODE_PRIVATE)

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            id
        }

    private val dao get() = AppDatabase.get(context).deviceDao()

    fun observeDevices(): Flow<List<Device>> = dao.observeDevices()

    fun observeThisDevice(): Flow<Device?> = dao.observeThisDevice()

    suspend fun getThisDevice(): Device? = dao.getThisDevice()

    /** Ensure this device exists in the table. Defaults to PRIMARY on first run. */
    suspend fun ensureRegistered(defaultName: String = android.os.Build.MODEL ?: "This device") {
        val existing = dao.getThisDevice()
        if (existing == null) {
            dao.upsert(
                Device(
                    deviceId = deviceId,
                    name = defaultName,
                    role = DeviceRole.PRIMARY,
                    online = true,
                    isThisDevice = true
                )
            )
        } else {
            dao.upsert(existing.copy(online = true, lastActiveAt = System.currentTimeMillis()))
        }
    }

    suspend fun role(): DeviceRole = dao.getThisDevice()?.role ?: DeviceRole.PRIMARY

    suspend fun setRole(role: DeviceRole) {
        val d = dao.getThisDevice() ?: return
        dao.upsert(d.copy(role = role))
    }

    suspend fun revoke(deviceId: String) = dao.revoke(deviceId)

    suspend fun addKnownDevice(device: Device) = dao.upsert(device)

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
    }
}
