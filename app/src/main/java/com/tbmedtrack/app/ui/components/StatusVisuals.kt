package com.tbmedtrack.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.tbmedtrack.app.data.db.DoseStatus
import com.tbmedtrack.app.ui.theme.StatusMissed
import com.tbmedtrack.app.ui.theme.StatusMissedContainer
import com.tbmedtrack.app.ui.theme.StatusNeutral
import com.tbmedtrack.app.ui.theme.StatusTaken
import com.tbmedtrack.app.ui.theme.StatusTakenContainer
import com.tbmedtrack.app.ui.theme.StatusUpcoming
import com.tbmedtrack.app.ui.theme.StatusUpcomingContainer

/** Visual mapping for a dose status. Never uses color alone: has icon + label too. */
data class StatusVisual(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val container: Color
)

fun statusVisual(status: DoseStatus, due: Boolean = false): StatusVisual = when (status) {
    DoseStatus.TAKEN -> StatusVisual("TAKEN", Icons.Filled.CheckCircle, StatusTaken, StatusTakenContainer)
    DoseStatus.MISSED -> StatusVisual("MISSED", Icons.Filled.Cancel, StatusMissed, StatusMissedContainer)
    DoseStatus.SNOOZED -> StatusVisual("SNOOZED", Icons.Filled.Snooze, StatusUpcoming, StatusUpcomingContainer)
    DoseStatus.SKIPPED -> StatusVisual("SKIPPED", Icons.Filled.Cancel, StatusNeutral, StatusMissedContainer)
    DoseStatus.REVERTED -> StatusVisual("NOT RECORDED", Icons.Filled.RadioButtonUnchecked, StatusNeutral, StatusUpcomingContainer)
    DoseStatus.SCHEDULED ->
        if (due) StatusVisual("DUE NOW", Icons.Filled.Notifications, StatusUpcoming, StatusUpcomingContainer)
        else StatusVisual("UPCOMING", Icons.Filled.RadioButtonUnchecked, StatusNeutral, StatusUpcomingContainer)
}
