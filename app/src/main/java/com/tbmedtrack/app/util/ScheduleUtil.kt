package com.tbmedtrack.app.util

import com.tbmedtrack.app.data.db.DoseSchedule
import com.tbmedtrack.app.data.db.Frequency
import com.tbmedtrack.app.data.db.Medicine
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Deterministic schedule computation. Uses the device local time zone at call
 * time so day/month/year/DST/timezone changes are handled implicitly.
 */
object ScheduleUtil {

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun today(): LocalDate = LocalDate.now(zone())

    fun epochDay(date: LocalDate): Long = date.toEpochDay()

    fun dateFromEpochDay(day: Long): LocalDate = LocalDate.ofEpochDay(day)

    /** epoch millis for a date + minutes-since-midnight in local zone. */
    fun toEpochMillis(date: LocalDate, timeMinutes: Int): Long {
        val ldt = LocalDateTime.of(date, LocalTime.of(timeMinutes / 60, timeMinutes % 60))
        return ldt.atZone(zone()).toInstant().toEpochMilli()
    }

    fun localDateTime(epochMillis: Long): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone()).toLocalDateTime()

    /** Does this schedule apply on the given date, respecting the medicine's date range and rule. */
    fun appliesOn(medicine: Medicine, schedule: DoseSchedule, date: LocalDate): Boolean {
        if (!schedule.enabled) return false
        val day = date.toEpochDay()
        if (day < medicine.startDate) return false
        medicine.endDate?.let { if (day > it) return false }

        // A special combination rule overrides the normal frequency calculation.
        medicine.scheduleRule?.let { rule ->
            return when (rule) {
                com.tbmedtrack.app.data.db.ScheduleRule.BEDAQUILINE_14_THEN_MWF ->
                    bedaquilineApplies(medicine.startDate, date)
                else -> normalApplies(medicine, schedule, date, day)
            }
        }
        return normalApplies(medicine, schedule, date, day)
    }

    private fun normalApplies(medicine: Medicine, schedule: DoseSchedule, date: LocalDate, day: Long): Boolean =
        when (schedule.frequency) {
            Frequency.EVERY_DAY -> true
            Frequency.SPECIFIC_DAYS -> {
                val iso = date.dayOfWeek.value // 1=Mon..7=Sun
                schedule.daysOfWeek.split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                    .contains(iso)
            }
            Frequency.EVERY_X_DAYS -> {
                val interval = schedule.intervalDays.coerceAtLeast(1)
                val anchor = if (schedule.anchorEpochDay > 0) schedule.anchorEpochDay else medicine.startDate
                val diff = day - anchor
                diff >= 0 && diff % interval == 0L
            }
        }

    /**
     * Bedaquiline rule: taken every day for the first 14 treatment days, then only on
     * Monday, Wednesday and Friday. Treatment day 1 = the start date.
     */
    fun bedaquilineApplies(startEpochDay: Long, date: LocalDate): Boolean {
        val treatmentDay = date.toEpochDay() - startEpochDay + 1 // 1-based
        if (treatmentDay < 1) return false
        if (treatmentDay <= 14) return true
        return when (date.dayOfWeek) {
            DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY -> true
            else -> false
        }
    }

    /** 1-based treatment day for a given date and start; 0 if before start. */
    fun treatmentDay(startEpochDay: Long, date: LocalDate): Int {
        val d = (date.toEpochDay() - startEpochDay + 1)
        return if (d < 1) 0 else d.toInt()
    }

    fun dayOfWeekLabel(iso: Int): String = when (iso) {
        1 -> "Mon"; 2 -> "Tue"; 3 -> "Wed"; 4 -> "Thu"; 5 -> "Fri"; 6 -> "Sat"; else -> "Sun"
    }

    fun formatTime(timeMinutes: Int): String {
        val lt = LocalTime.of(timeMinutes / 60, timeMinutes % 60)
        val hour12 = when {
            lt.hour == 0 -> 12
            lt.hour > 12 -> lt.hour - 12
            else -> lt.hour
        }
        val ampm = if (lt.hour < 12) "AM" else "PM"
        return String.format("%02d:%02d %s", hour12, lt.minute, ampm)
    }

    fun startOfWeek(date: LocalDate, weekStartsMonday: Boolean): LocalDate {
        val startDow = if (weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY
        var d = date
        while (d.dayOfWeek != startDow) d = d.minusDays(1)
        return d
    }
}
