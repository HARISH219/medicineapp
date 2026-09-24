package com.tbmedtrack.app.sync

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for the backend base URL and this device's session token. The token is
 * obtained from the backend during pairing/sign-in and is never a Turso credential — the Turso
 * token lives only on the server (see docs/BACKEND.md). Values are stored with
 * EncryptedSharedPreferences so they are not readable from a plain file dump.
 */
class SecureStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tbmedtrack_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Backend base URL. Defaults to the app's built-in backend so cloud sync is ALWAYS ON without
     * the user entering anything. A stored value (from manual setup) overrides the default.
     */
    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null) ?: DEFAULT_BASE_URL
        set(value) { prefs.edit().putString(KEY_BASE_URL, value).apply() }

    var sessionToken: String?
        get() = prefs.getString(KEY_SESSION_TOKEN, null)
        set(value) { prefs.edit().putString(KEY_SESSION_TOKEN, value).apply() }

    fun clear() = prefs.edit().clear().apply()

    val isConfigured: Boolean get() = !baseUrl.isNullOrBlank() && !sessionToken.isNullOrBlank()

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SESSION_TOKEN = "session_token"

        /** Built-in backend so the app is always connected without any setup. */
        const val DEFAULT_BASE_URL = "https://medicineapp-ashy.vercel.app"
    }
}
