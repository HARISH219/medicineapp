package com.tbmedtrack.app.data

import com.tbmedtrack.app.data.db.FoodEvent
import com.tbmedtrack.app.data.db.FoodEventDao
import com.tbmedtrack.app.data.db.Medicine
import com.tbmedtrack.app.util.ScheduleUtil

/**
 * Food → medicine gap logic. Records "I have eaten" events and computes, per dose, the
 * food-adjusted EARLIEST ELIGIBLE time using:
 *
 *     eligible      = max(scheduled, latestApplicableFood + effectiveGap)
 *     earlyReminder = eligible - 10 min
 *     criticalStart = eligible + criticalGrace
 *
 * The LATEST food event that occurred before the eligible calculation controls the wait; a
 * newer meal resets it. Fully local/offline — no network needed for the calculation.
 */
class FoodRepository(
    private val foodDao: FoodEventDao,
    private val settingsProvider: suspend () -> FoodGapSettings
) {
    /** Snapshot of the settings this repository needs. */
    data class FoodGapSettings(
        val defaultFoodGapMinutes: Int,
        val criticalGraceMinutes: Int
    )

    /** Minutes before the eligible time to send the "coming up" reminder. */
    val earlyReminderLeadMinutes = 10

    @Volatile var currentDeviceId: String = ""

    /** Record that food was eaten at [foodTimeMillis] (defaults to now). Local only. */
    suspend fun recordFood(foodTimeMillis: Long = System.currentTimeMillis()): FoodEvent {
        val date = ScheduleUtil.localDateTime(foodTimeMillis).toLocalDate()
        val event = FoodEvent(
            deviceId = currentDeviceId,
            foodTimeMillis = foodTimeMillis,
            epochDay = date.toEpochDay(),
            recordedAt = System.currentTimeMillis()
        )
        val id = foodDao.insert(event)
        return event.copy(id = id)
    }

    suspend fun latestFood(): FoodEvent? = foodDao.latest()

    suspend fun foodEventsForDay(epochDay: Long): List<FoodEvent> = foodDao.forDay(epochDay)

    suspend fun recentFoodEvents(limit: Int = 60): List<FoodEvent> = foodDao.recent(limit)

    /** The effective food gap (minutes) for a medicine, resolving the -1 "inherit default". */
    suspend fun effectiveGapMinutes(medicine: Medicine): Int {
        val configured = medicine.foodGapMinutes
        return when {
            configured > 0 -> configured
            configured == 0 -> 0
            else -> settingsProvider().defaultFoodGapMinutes // -1 -> inherit global default
        }
    }

    /**
     * Food-adjusted timing for one dose of [medicine] scheduled at [scheduledMillis].
     * Returns null when the medicine has no food gap (nothing to adjust).
     *
     * The controlling food event is the latest one at/before the dose's eligible window — we use
     * the latest food recorded so far (before "now" is irrelevant; the newest meal wins). To keep
     * it deterministic we take the latest food event overall on the dose's day (or earlier).
     */
    suspend fun timingFor(medicine: Medicine, scheduledMillis: Long): FoodTiming? {
        val gap = effectiveGapMinutes(medicine)
        if (gap <= 0) return null

        // Latest food event recorded up to now controls the wait. We look at the newest food
        // event overall; if none, there is no food adjustment yet.
        val latest = foodDao.latest() ?: return FoodTiming(
            scheduledMillis = scheduledMillis,
            foodTimeMillis = null,
            gapMinutes = gap,
            eligibleMillis = scheduledMillis,
            earlyReminderMillis = scheduledMillis - earlyReminderLeadMinutes * 60_000L,
            criticalStartMillis = scheduledMillis + settingsProvider().criticalGraceMinutes * 60_000L,
            foodAdjusted = false
        )

        val grace = settingsProvider().criticalGraceMinutes
        val foodEligible = latest.foodTimeMillis + gap * 60_000L
        val eligible = maxOf(scheduledMillis, foodEligible)
        val adjusted = eligible > scheduledMillis
        return FoodTiming(
            scheduledMillis = scheduledMillis,
            foodTimeMillis = latest.foodTimeMillis,
            gapMinutes = gap,
            eligibleMillis = eligible,
            earlyReminderMillis = eligible - earlyReminderLeadMinutes * 60_000L,
            criticalStartMillis = eligible + grace * 60_000L,
            foodAdjusted = adjusted
        )
    }

    /**
     * Food-adjusted timing computed from an EXPLICIT food event time (used when we just recorded
     * food and want to reschedule without re-reading the DB).
     */
    suspend fun timingForWithFood(
        medicine: Medicine,
        scheduledMillis: Long,
        foodTimeMillis: Long
    ): FoodTiming? {
        val gap = effectiveGapMinutes(medicine)
        if (gap <= 0) return null
        val grace = settingsProvider().criticalGraceMinutes
        val eligible = maxOf(scheduledMillis, foodTimeMillis + gap * 60_000L)
        return FoodTiming(
            scheduledMillis = scheduledMillis,
            foodTimeMillis = foodTimeMillis,
            gapMinutes = gap,
            eligibleMillis = eligible,
            earlyReminderMillis = eligible - earlyReminderLeadMinutes * 60_000L,
            criticalStartMillis = eligible + grace * 60_000L,
            foodAdjusted = eligible > scheduledMillis
        )
    }
}

/** Result of a food-gap calculation for a single dose. All times are epoch millis. */
data class FoodTiming(
    val scheduledMillis: Long,
    /** the food event time that controlled the wait, or null if no food recorded */
    val foodTimeMillis: Long?,
    val gapMinutes: Int,
    /** earliest time the medicine may be taken */
    val eligibleMillis: Long,
    /** 10-minute "coming up" reminder time */
    val earlyReminderMillis: Long,
    /** when the critical escalation may begin (eligible + grace) */
    val criticalStartMillis: Long,
    /** true when the food gap pushed the eligible time past the original schedule */
    val foodAdjusted: Boolean
) {
    /** True if, at [now], the medicine is still inside the food-gap waiting window. */
    fun isWaiting(now: Long = System.currentTimeMillis()): Boolean = now < eligibleMillis
    fun remainingMillis(now: Long = System.currentTimeMillis()): Long = (eligibleMillis - now).coerceAtLeast(0)
}
