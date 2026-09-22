package com.tbmedtrack.app.ui.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tbmedtrack.app.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class CloudSyncUiState(
    val backendUrl: String = "",
    val configured: Boolean = false,
    val testing: Boolean = false,
    val message: String? = null
)

class CloudSyncViewModel(app: Application) : AndroidViewModel(app) {

    private val sync = ServiceLocator.syncManager(app)
    private val store = sync.secureStore()

    private val _state = MutableStateFlow(
        CloudSyncUiState(
            backendUrl = store.baseUrl ?: "",
            configured = !store.baseUrl.isNullOrBlank()
        )
    )
    val state: StateFlow<CloudSyncUiState> = _state.asStateFlow()

    fun setUrl(url: String) {
        _state.value = _state.value.copy(backendUrl = url, message = null)
    }

    /** Save the backend URL, switch to the real client, and test /health. */
    fun saveAndTest() {
        val raw = _state.value.backendUrl.trim().trimEnd('/')
        if (raw.isBlank()) {
            _state.value = _state.value.copy(message = "Enter your backend URL first."); return
        }
        if (!raw.startsWith("https://")) {
            _state.value = _state.value.copy(message = "URL must start with https://"); return
        }
        _state.value = _state.value.copy(testing = true, message = null)
        viewModelScope.launch {
            store.baseUrl = raw
            sync.reconfigure()
            val ok = testHealth(raw)
            if (ok && store.sessionToken.isNullOrBlank()) {
                // First connect on this device: establish it as PRIMARY and get a session token.
                val deviceRepo = ServiceLocator.deviceRepository(getApplication())
                sync.client.bootstrapPrimary(deviceRepo.deviceId, android.os.Build.MODEL ?: "My phone")
            }
            val connected = ok && !store.sessionToken.isNullOrBlank()
            _state.value = _state.value.copy(
                testing = false,
                configured = connected,
                message = when {
                    connected -> "Connected to backend ✓"
                    ok -> "Backend reachable but sign-in failed. Check env vars (Turso) on the server."
                    else -> "Saved, but /health did not respond. Check the URL and that the backend is deployed."
                }
            )
            if (connected) sync.syncNow()
        }
    }

    fun disconnect() {
        store.clear()
        sync.reconfigure()
        _state.value = CloudSyncUiState(backendUrl = "", configured = false, message = "Disconnected. Data stays local.")
    }

    private suspend fun testHealth(base: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$base/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }
}
