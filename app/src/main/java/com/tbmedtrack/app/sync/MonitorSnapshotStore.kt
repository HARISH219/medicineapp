package com.tbmedtrack.app.sync

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable last-good monitor snapshot used across process death and offline restarts. */
class MonitorSnapshotStore(context: Context) {
    private val prefs = context.getSharedPreferences("tbmedtrack_monitor_snapshot", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): MonitorSnapshot = runCatching {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return@runCatching MonitorSnapshot()
        json.decodeFromString<MonitorSnapshot>(raw)
    }.getOrDefault(MonitorSnapshot())

    fun save(snapshot: MonitorSnapshot): Boolean = runCatching {
        prefs.edit().putString(KEY_SNAPSHOT, json.encodeToString(snapshot)).commit()
    }.getOrDefault(false)

    private companion object {
        const val KEY_SNAPSHOT = "snapshot"
    }
}
