package com.tbmedtrack.app.ui.wizard

import android.app.Application
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.data.db.DeviceRole
import com.tbmedtrack.app.data.settings.DeviceRoleValue
import com.tbmedtrack.app.util.PermissionUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Live readiness of each required permission (recomputed on resume). */
data class WizardPermissionState(
    val notifications: Boolean = false,
    val exactAlarms: Boolean = false,
    val fullScreen: Boolean = false,
    val battery: Boolean = false
)

data class ConnectState(
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null
)

class SetupWizardViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = ServiceLocator.settingsRepository(app)
    private val deviceRepo = ServiceLocator.deviceRepository(app)
    private val syncManager = ServiceLocator.syncManager(app)

    private val _perms = MutableStateFlow(WizardPermissionState())
    val perms: StateFlow<WizardPermissionState> = _perms.asStateFlow()

    private val _connect = MutableStateFlow(ConnectState())
    val connect: StateFlow<ConnectState> = _connect.asStateFlow()

    private var player: MediaPlayer? = null

    /** Recompute real OS permission state — call on screen resume and after returning from settings. */
    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        _perms.value = WizardPermissionState(
            notifications = PermissionUtil.hasNotificationPermission(ctx),
            exactAlarms = PermissionUtil.canScheduleExactAlarms(ctx),
            fullScreen = PermissionUtil.canUseFullScreenIntent(ctx),
            battery = PermissionUtil.isIgnoringBatteryOptimizations(ctx)
        )
    }

    /** Play a short test of the alarm sound so the user can verify audibility. */
    fun playTestAlarm() {
        stopTestAlarm()
        val ctx = getApplication<Application>()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(ctx, uri)
                isLooping = false
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }
    }

    fun stopTestAlarm() {
        runCatching { player?.stop(); player?.release() }
        player = null
    }

    /** Choose MAIN role, persist, and bootstrap the account so pairing codes work. */
    fun chooseMain(onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setDeviceRole(DeviceRoleValue.MAIN)
            deviceRepo.setRole(DeviceRole.PRIMARY)
            settings.setSetupWizardDone(true)
            onDone()
        }
    }

    /** Choose SECONDARY role and attempt to connect using a pairing code. */
    fun connectSecondary(code: String, onDone: () -> Unit) {
        val clean = code.replace("-", "").trim()
        if (clean.length < 6) {
            _connect.value = ConnectState(error = "Enter the full connection code.")
            return
        }
        viewModelScope.launch {
            _connect.value = ConnectState(connecting = true)
            // Redeem through the sync layer; requires a configured backend URL on this device.
            val result = runCatching {
                syncManager.reconfigure()
                syncManager.redeemCode(clean, android.os.Build.MODEL ?: "Monitor")
            }
            result.fold(
                onSuccess = { ok ->
                    if (ok) {
                        settings.setDeviceRole(DeviceRoleValue.SECONDARY)
                        deviceRepo.setRole(DeviceRole.MONITOR)
                        settings.setSetupWizardDone(true)
                        _connect.value = ConnectState(connected = true)
                        onDone()
                    } else {
                        _connect.value = ConnectState(error = "Could not connect. Check the code and that a backend is configured.")
                    }
                },
                onFailure = {
                    _connect.value = ConnectState(error = it.message ?: "Connection failed.")
                }
            )
        }
    }

    override fun onCleared() {
        stopTestAlarm()
        super.onCleared()
    }
}
