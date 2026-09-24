package com.tbmedtrack.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        Medicine::class, DoseSchedule::class, MedicationLog::class, Device::class,
        MedicationEventAudit::class, SyncOperation::class, MedicationPhase::class,
        FoodEvent::class
    ],
    version = 7,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun medicineDao(): MedicineDao
    abstract fun logDao(): LogDao
    abstract fun deviceDao(): DeviceDao
    abstract fun auditDao(): AuditDao
    abstract fun syncOperationDao(): SyncOperationDao
    abstract fun phaseDao(): PhaseDao
    abstract fun foodEventDao(): FoodEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tbmedtrack.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
