package com.tbmedtrack.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Role of an authorized device. */
enum class DeviceRole { PRIMARY, MONITOR }

/**
 * An authorized device that participates in this account. The PRIMARY device takes
 * medication and manages the regimen; MONITOR devices (e.g. a family member) can
 * only view status and receive alerts.
 */
@Entity(tableName = "devices")
data class Device(
    @PrimaryKey val deviceId: String,
    val name: String,
    val role: DeviceRole,
    val online: Boolean = false,
    val lastActiveAt: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    /** true if this row represents the device the app is currently running on */
    val isThisDevice: Boolean = false,
    val revoked: Boolean = false
)
