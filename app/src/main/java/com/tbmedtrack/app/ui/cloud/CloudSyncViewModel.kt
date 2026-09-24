package com.tbmedtrack.app.ui.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.sync.SyncCheckResult
import com.tbmedtrack.app.sync.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class CloudSyncUiState(
    /** live always-on status */
    val status: SyncStatus = SyncStatus(),
    /** result of the last CHECK SYNC, if any */
    val checkResult: SyncCheckResult? = null,
    val checking: Boolean = false,
    /** first-time backend URL entry (only shown until configured) */
    val backendUrl: String = "",
    val connecting: Boolean = false,
    val message: String? = null
)

/**
 * Cloud sync is ALWAYS ON — there is no enable/disable. This VM exposes the live status and a
 * single CHECK SYNC action, plus a first-time backend-URL connect for devices that have not yet
 * pointed at a backend.
 */
class CloudSyncViewModel(app: Application) : AndroidViewModel(app) {

    private val sync = ServiceLocator.syncManager(app)
    private val store = sync.secureStore()

    private val _state = MutableStateFlow(CloudSyncUiState(backendUrl = store.baseUrl ?: ""))
    val state: StateFlow<CloudSyncUiState> = _state.asStateFlow()

    init {
        // Mirror the SyncManager status flow into our UI state.
        viewModelScope.launch {
            sync.status.collect { s -> _state.value = _state.value.copy(status = s) }
        }
        sync.refreshStatus()
        // Kick a background sync so status reflects reality when the screen opens.
        viewModelScope.launch { runCatching { sync.syncNow() } }
    }

    fun setUrl(url: String) { _state.value = _state.value.copy(backendUrl = url, message = null) }

    /** Run a manual CHECK SYNC and show the detailed result. */
    fun checkSync() {
        if (_state.value.checking) return
        _state.value = _state.value.copy(checking = true, message = null)
        viewModelScope.launch {
            val result = runCatching { sync.checkSync() }.getOrNull()
            _state.value = _state.value.copy(checking = false, checkResult = result)
        }
    }

    /** First-time connect: save the backend URL, bootstrap this device, and sync. No on/off. */
    fun connect() {
        val raw = _state.value.backendUrl.trim().trimEnd('/')
        if (raw.isBlank()) { _state.value = _state.value.copy(message = "Enter your backend URL first."); return }
        if (!raw.startsWith("https://")) { _state.value = _state.value.copy(message = "URL must start with https://"); return }
        _state.value = _state.value.copy(connecting = true, message = null)
        viewModelScope.launch {
            store.baseUrl = raw
            sync.reconfigure()
            val ok = testHealth(raw)
            if (ok && store.sessionToken.isNullOrBlank()) {
                val deviceRepo = ServiceLocator.deviceRepository(getApplication())
                sync.client.bootstrapPrimary(deviceRepo.deviceId, android.os.Build.MODEL ?: "My phone")
            }
            val connected = ok && !store.sessionToken.isNullOrBlank()
            _state.value = _state.value.copy(
                connecting = false,
                message = when {
                    connected -> "Connected ✓ Cloud sync is now active."
                    ok -> "Backend reachable but sign-in failed. Check the server's Turso env vars."
                    else -> "/health did not respond. Check the URL and that the backend is deployed."
                }
            )
            if (connected) { sync.syncNow(); sync.refreshStatus() }
        }
    }

    private suspend fun testHealth(base: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$base/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000; conn.requestMethod = "GET"
            val code = conn.responseCode; conn.disconnect(); code in 200..299
        }.getOrDefault(false)
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
