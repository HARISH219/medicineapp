package com.tbmedtrack.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.graphics.vector.ImageVector

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CALENDAR = "calendar"
    const val MEDICINES = "medicines"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val ADD_EDIT = "add_edit"          // ?id={id}
    const val HISTORY = "history"            // ?day={epochDay}
    const val MEDICINE_HISTORY = "med_history" // /{id}
    const val TREATMENT = "treatment"
    const val DEVICES = "devices"
    const val MONITOR = "monitor"
    const val IMPORT_HISTORY = "import_history"
    const val TIMELINE = "timeline"
    const val CLOUD_SYNC = "cloud_sync"
    const val PHASE_PREVIEW = "phase_preview" // /{id}
    const val EVENT_DETAIL = "event_detail"   // /{day}/{time}
}

/**
 * Bottom navigation destinations, in display order:
 * Home · Schedule · (+) · Medicines · Stats · Settings.
 * The center (+) is an elevated action, not a BottomDest entry.
 */
enum class BottomDest(val route: String, val label: String, val icon: ImageVector) {
    HOME(Routes.HOME, "Home", Icons.Outlined.Home),
    SCHEDULE(Routes.CALENDAR, "Schedule", Icons.Outlined.Schedule),
    MEDICINES(Routes.MEDICINES, "Medicines", Icons.Outlined.Medication),
    STATS(Routes.STATS, "Stats", Icons.Outlined.BarChart),
    SETTINGS(Routes.SETTINGS, "Settings", Icons.Outlined.Settings)
}
