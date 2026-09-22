package com.tbmedtrack.app.data.db

import androidx.room.Embedded
import androidx.room.Relation

/** A medicine together with all of its dose schedules. */
data class MedicineWithSchedules(
    @Embedded val medicine: Medicine,
    @Relation(
        parentColumn = "id",
        entityColumn = "medicineId"
    )
    val schedules: List<DoseSchedule>
)
