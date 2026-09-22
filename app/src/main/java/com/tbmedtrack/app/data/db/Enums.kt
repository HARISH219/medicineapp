package com.tbmedtrack.app.data.db

/** Status of a single scheduled dose in the log. */
enum class DoseStatus {
    SCHEDULED,
    TAKEN,
    MISSED,
    SNOOZED,
    SKIPPED,
    /** transient audit marker; the live event returns to SCHEDULED after a revert */
    REVERTED
}

/** How often a dose schedule repeats. */
enum class Frequency {
    EVERY_DAY,
    SPECIFIC_DAYS,   // uses daysOfWeek
    EVERY_X_DAYS     // uses intervalDays
}

/** Dose unit choices. */
object DoseUnits {
    val ALL = listOf("mg", "g", "ml", "tablet", "capsule", "other")
}
