package com.tbmedtrack.app.data.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter
    fun statusToString(status: DoseStatus): String = status.name

    @TypeConverter
    fun stringToStatus(value: String): DoseStatus = DoseStatus.valueOf(value)

    @TypeConverter
    fun freqToString(f: Frequency): String = f.name

    @TypeConverter
    fun stringToFreq(value: String): Frequency = Frequency.valueOf(value)

    @TypeConverter
    fun roleToString(r: DeviceRole): String = r.name

    @TypeConverter
    fun stringToRole(value: String): DeviceRole = DeviceRole.valueOf(value)
}
