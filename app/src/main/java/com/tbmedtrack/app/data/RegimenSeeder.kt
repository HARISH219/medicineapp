package com.tbmedtrack.app.data

import com.tbmedtrack.app.data.db.DoseSchedule
import com.tbmedtrack.app.data.db.Frequency
import com.tbmedtrack.app.data.db.Medicine
import com.tbmedtrack.app.data.db.ScheduleRule
import com.tbmedtrack.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Seeds the specific MDR-TB regimen once, on first launch. Everything created here is
 * an ordinary editable record — the user can change or delete any of it. The app does
 * not treat these drugs/doses as medically authoritative; it only follows the schedule.
 *
 * Regimen (as configured by the user's prescription):
 *  - Treatment start: 16 Sep 2026
 *  - 10:00 AM daily: Linezolid 600 mg, Moxifloxacin 400 mg, Pretomanid 200 mg
 *  - 10:00 AM: Bedaquiline 100 mg — every day for the first 14 days, then Mon/Wed/Fri
 *  - Bedtime (configurable, default 22:00): Pyridoxine / Vitamin B6 25 mg
 */
class RegimenSeeder(
    private val repo: MedRepository,
    private val settings: SettingsRepository
) {

    companion object {
        val TREATMENT_START: LocalDate = LocalDate.of(2026, 9, 16)
    }

    suspend fun seedIfNeeded() {
        val s = settings.settings.first()
        if (s.regimenSeeded) return

        val startDay = TREATMENT_START.toEpochDay()
        val morning = s.morningDoseMinutes
        val night = s.nightMedicineMinutes

        // Daily morning medicines
        seedMedicine("Linezolid", "600", "mg", morning, startDay, rule = null)
        seedMedicine("Moxifloxacin", "400", "mg", morning, startDay, rule = null)
        seedMedicine("Pretomanid", "200", "mg", morning, startDay, rule = null)
        // Bedaquiline with the 14-day-then-MWF rule
        seedMedicine("Bedaquiline", "100", "mg", morning, startDay, rule = ScheduleRule.BEDAQUILINE_14_THEN_MWF)
        // Bedtime vitamin
        seedMedicine("Pyridoxine / Vitamin B6", "25", "mg", night, startDay, rule = null, notes = "Bedtime")

        settings.setRegimenSeeded(true)
        if (s.treatmentStartDay == 0L) settings.setTreatmentStartDay(startDay)
    }

    private suspend fun seedMedicine(
        name: String,
        dose: String,
        unit: String,
        timeMinutes: Int,
        startDay: Long,
        rule: String?,
        notes: String = ""
    ) {
        val medicine = Medicine(
            name = name,
            dose = dose,
            unit = unit,
            type = "Tablet",
            foodTiming = "Doesn't matter",
            notes = notes,
            active = true,
            partOfTbRegimen = true,
            scheduleRule = rule,
            startDate = startDay,
            endDate = null
        )
        val schedule = DoseSchedule(
            medicineId = 0,
            timeMinutes = timeMinutes,
            frequency = Frequency.EVERY_DAY,
            anchorEpochDay = startDay,
            enabled = true
        )
        repo.upsertMedicine(medicine, listOf(schedule))
    }
}
