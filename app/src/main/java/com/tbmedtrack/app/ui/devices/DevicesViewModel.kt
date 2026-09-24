package com.tbmedtrack.app.ui.devices

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.Device
import com.tbmedtrack.app.data.db.DeviceRole
import com.tbmedtrack.app.sync.AuthCode
import com.tbmedtrack.app.sync.AuthCodeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DevicesUiState(
    val authCode: AuthCode? = null,
    val message: String? = null,
    val backendConfigured: Boolean = false,
    val refreshing: Boolean = false,
    /** Cloud's latest sync version, from /v1/sync-status. 0 if unknown. */
    val cloudVersion: Long = 0L,
    /** Per-device sync version + up-to-date flag, keyed by deviceId. */
    val deviceSync: Map<String, DeviceSyncInfo> = emptyMap()
)

/** Sync-version details for a single device, surfaced next to its row. */
data class DeviceSyncInfo(
    val lastSyncedVersion: Long,
    val upToDate: Boolean
)

class DevicesViewModel(app: Application) : AndroidViewModel(app) {

    private val deviceRepo = ServiceLocator.deviceRepository(app)
    private val sync = ServiceLocator.syncManager(app)

    val devices: StateFlow<List<Device>> =
        deviceRepo.observeDevices().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val thisDevice: StateFlow<Device?> =
        deviceRepo.observeThisDevice().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _ui = MutableStateFlow(DevicesUiState(backendConfigured = sync.client.isConfigured))
    val ui: StateFlow<DevicesUiState> = _ui.asStateFlow()

    init {
        // Pull the authoritative device list from the backend so authorized monitors
        // appear here, not just this device's local Room row.
        refreshDevices()
    }

    /**
     * Hydrate the authorized-device list from the backend's /v1/sync-status.
     * The backend is the source of truth for who is authorized; the local Room table
     * only ever knew about THIS device, which is why monitors never showed up before.
     */
    fun refreshDevices() {
        if (!sync.client.isConfigured) {
            _ui.value = _ui.value.copy(backendConfigured = false)
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(refreshing = true, backendConfigured = true)
            val result = sync.client.syncStatus()
            result.fold(
                onSuccess = { status ->
                    val thisId = deviceRepo.deviceId
                    val syncMap = HashMap<String, DeviceSyncInfo>()
                    status.devices.forEach { remote ->
                        val role = when (remote.role.uppercase()) {
                            "PRIMARY", "MAIN" -> DeviceRole.PRIMARY
                            else -> DeviceRole.MONITOR
                        }
                        deviceRepo.addKnownDevice(
                            Device(
                                deviceId = remote.deviceId,
                                name = remote.name,
                                role = role,
                                online = remote.online,
                                lastActiveAt = remote.lastActiveAt,
                                isThisDevice = remote.deviceId == thisId || remote.isThisDevice,
                                revoked = false
                            )
                        )
                        syncMap[remote.deviceId] = DeviceSyncInfo(
                            lastSyncedVersion = remote.lastSyncedVersion,
                            upToDate = remote.upToDate
                        )
                    }
                    _ui.value = _ui.value.copy(
                        refreshing = false,
                        cloudVersion = status.cloudVersion,
                        deviceSync = syncMap
                    )
                },
                onFailure = {
                    _ui.value = _ui.value.copy(
                        refreshing = false,
                        message = it.message ?: "Could not refresh devices"
                    )
                }
            )
        }
    }

    /** Primary device generates an authorization code for a new device. */
    fun generateAuthCode() {
        viewModelScope.launch {
            if (sync.client.isConfigured) {
                val result = sync.client.createAuthCode()
                result.fold(
                    onSuccess = { _ui.value = _ui.value.copy(authCode = it, message = null) },
                    onFailure = { _ui.value = _ui.value.copy(message = it.message) }
                )
            } else {
                // Local code for the UI flow. A second device can only truly join once a
                // backend is deployed (see docs/BACKEND.md).
                val code = AuthCodeManager.generate()
                _ui.value = _ui.value.copy(
                    authCode = code,
                    message = "No sync backend configured yet — this code is display-only until you deploy the backend."
                )
            }
        }
    }

    /** Second device redeems a code to join as a MONITOR. */
    fun redeemCode(code: String, deviceName: String) {
        viewModelScope.launch {
            val result = sync.client.redeemAuthCode(code, deviceName)
            _ui.value = result.fold(
                onSuccess = {
                    deviceRepo.setRole(DeviceRole.MONITOR)
                    _ui.value.copy(message = "Device authorized")
                },
                onFailure = { _ui.value.copy(message = it.message ?: "Could not authorize (backend required)") }
            )
            if (result.isSuccess) refreshDevices()
        }
    }

    fun revoke(deviceId: String) {
        viewModelScope.launch {
            deviceRepo.revoke(deviceId)
            sync.client.revokeDevice(deviceId)
            _ui.value = _ui.value.copy(message = "Device removed")
            refreshDevices()
        }
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }
}
