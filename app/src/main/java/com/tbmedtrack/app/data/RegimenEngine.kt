package com.tbmedtrack.app.data

import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.data.model.ScheduledDose
import com.tbmedtrack.app.util.ScheduleUtil
import java.time.LocalDate

/**
 * A "combination" is the set of medicines required at one clock time on a given day.
 * For this regimen the key event is the 10:00 AM combination, whose size
 * changes over time (4 medicines during the first 14 days and on Mon/Wed/Fri after
 * that, otherwise 3).
 *
 * This engine derives combinations purely from configured medicine + schedule records
 * via [MedRepository.getDosesForDay]. It makes no medical decisions.
 */
class RegimenEngine {

    data class Combination(
        val timeMinutes: Int,
        val scheduledMillis: Long,
        val epochDay: Long,
        val doses: List<ScheduledDose>
    ) {
        val required: Int get() = doses.size
        val takenCount: Int get() = doses.count { it.status == DoseStatus.TAKEN }
        val allTaken: Boolean get() = doses.isNotEmpty() && doses.all { it.status == DoseStatus.TAKEN }
        val pending: Boolean get() = doses.any {
            it.status == DoseStatus.SCHEDULED || it.status == DoseStatus.SNOOZED ||
                it.status == DoseStatus.MISSED
        }
        /** Ordered medicine display names for this combination. */
        val medicineNames: List<String> get() = doses.map { it.medicineName }
    }

    /** Group the day's doses into combinations by clock time. */
    fun combinationsFor(doses: List<ScheduledDose>): List<Combination> =
        doses.groupBy { it.timeMinutes }
            .map { (time, list) ->
                Combination(
                    timeMinutes = time,
                    scheduledMillis = list.first().scheduledMillis,
                    epochDay = list.first().epochDay,
                    doses = list.sortedBy { it.medicineName }
                )
            }
            .sortedBy { it.timeMinutes }

    /** The combination whose clock time is closest to (but ideally the primary morning) time. */
    fun primaryMorningCombination(combinations: List<Combination>): Combination? =
        combinations.firstOrNull { it.timeMinutes in (9 * 60)..(12 * 60) }
            ?: combinations.minByOrNull { it.timeMinutes }
}
