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
    val backendConfigured: Boolean = false
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
        }
    }

    fun revoke(deviceId: String) {
        viewModelScope.launch {
            deviceRepo.revoke(deviceId)
            sync.client.revokeDevice(deviceId)
            _ui.value = _ui.value.copy(message = "Device removed")
        }
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }
}
