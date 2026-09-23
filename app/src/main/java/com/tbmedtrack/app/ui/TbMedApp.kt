package com.tbmedtrack.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tbmedtrack.app.ui.calendar.CalendarScreen
import com.tbmedtrack.app.ui.components.ScreenBackground
import com.tbmedtrack.app.ui.history.HistoryScreen
import com.tbmedtrack.app.ui.home.EventDetailScreen
import com.tbmedtrack.app.ui.home.HomeScreen
import com.tbmedtrack.app.ui.medicines.AddEditScreen
import com.tbmedtrack.app.ui.medicines.MedicineHistoryScreen
import com.tbmedtrack.app.ui.medicines.MedicinesScreen
import com.tbmedtrack.app.ui.navigation.BottomDest
import com.tbmedtrack.app.ui.navigation.Routes
import com.tbmedtrack.app.ui.settings.SettingsScreen
import com.tbmedtrack.app.ui.stats.StatsScreen
import com.tbmedtrack.app.ui.theme.AccentPurple
import com.tbmedtrack.app.ui.theme.DarkOnSurfaceMuted
import com.tbmedtrack.app.ui.theme.Indigo
import com.tbmedtrack.app.ui.treatment.TreatmentScreen
import com.tbmedtrack.app.util.ScheduleUtil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TbMedApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val bottomRoutes = BottomDest.entries.map { it.route }
    val showBottomBar = currentRoute in bottomRoutes
    val isDetail = currentRoute?.startsWith(Routes.ADD_EDIT) == true ||
        currentRoute?.startsWith(Routes.HISTORY) == true ||
        currentRoute?.startsWith(Routes.MEDICINE_HISTORY) == true ||
        currentRoute == Routes.TREATMENT ||
        currentRoute == Routes.DEVICES ||
        currentRoute == Routes.MONITOR ||
        currentRoute == Routes.TIMELINE ||
        currentRoute == Routes.IMPORT_HISTORY ||
        currentRoute == Routes.CLOUD_SYNC ||
        currentRoute?.startsWith(Routes.PHASE_PREVIEW) == true ||
        currentRoute?.startsWith(Routes.EVENT_DETAIL) == true

    ScreenBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (isDetail) {
                    TopAppBar(
                        title = { Text(topTitle(currentRoute)) },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            },
            bottomBar = {
                if (showBottomBar) {
                    FloatingBottomNav(
                        currentRoute = currentRoute,
                        onSelect = { route ->
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onAdd = { navController.navigate("${Routes.ADD_EDIT}?id=0") }
                    )
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(padding)
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        onAddMedicine = { navController.navigate("${Routes.ADD_EDIT}?id=0") },
                        onOpenCalendar = { navController.navigate(Routes.CALENDAR) },
                        onOpenHistory = { navController.navigate("${Routes.HISTORY}?day=${ScheduleUtil.today().toEpochDay()}") },
                        onOpenTreatment = { navController.navigate(Routes.TREATMENT) },
                        onOpenEvent = { ev ->
                            navController.navigate("${Routes.EVENT_DETAIL}/${ev.epochDay}/${ev.timeMinutes}")
                        }
                    )
                }
                composable(Routes.CALENDAR) { CalendarScreen() }
                composable(Routes.MEDICINES) {
                    MedicinesScreen(
                        onAdd = { navController.navigate("${Routes.ADD_EDIT}?id=0") },
                        onEdit = { id -> navController.navigate("${Routes.ADD_EDIT}?id=$id") },
                        onHistory = { id -> navController.navigate("${Routes.MEDICINE_HISTORY}/$id") }
                    )
                }
                composable(Routes.STATS) { StatsScreen() }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        onOpenDevices = { navController.navigate(Routes.DEVICES) },
                        onOpenMonitor = { navController.navigate(Routes.MONITOR) },
                        onImportHistory = { navController.navigate(Routes.IMPORT_HISTORY) },
                        onOpenCloudSync = { navController.navigate(Routes.CLOUD_SYNC) }
                    )
                }
                composable(Routes.TREATMENT) {
                    TreatmentScreen(
                        onOpenTimeline = { navController.navigate(Routes.TIMELINE) },
                        onViewSchedule = { id -> navController.navigate("${Routes.PHASE_PREVIEW}/$id") }
                    )
                }
                composable(Routes.DEVICES) { com.tbmedtrack.app.ui.devices.DevicesScreen() }
                composable(Routes.MONITOR) { com.tbmedtrack.app.ui.monitor.MonitorScreen() }
                composable(Routes.TIMELINE) { com.tbmedtrack.app.ui.timeline.TimelineScreen() }
                composable(Routes.CLOUD_SYNC) { com.tbmedtrack.app.ui.cloud.CloudSyncScreen() }
                composable(
                    route = "${Routes.PHASE_PREVIEW}/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) { entry ->
                    com.tbmedtrack.app.ui.phase.PhasePreviewScreen(medicineId = entry.arguments?.getLong("id") ?: 0L)
                }
                composable(Routes.IMPORT_HISTORY) {
                    com.tbmedtrack.app.ui.setup.SetupScreen(onDone = { navController.popBackStack() })
                }

                composable(
                    route = "${Routes.EVENT_DETAIL}/{day}/{time}",
                    arguments = listOf(
                        navArgument("day") { type = NavType.LongType },
                        navArgument("time") { type = NavType.IntType }
                    )
                ) { entry ->
                    EventDetailScreen(
                        epochDay = entry.arguments?.getLong("day") ?: ScheduleUtil.today().toEpochDay(),
                        timeMinutes = entry.arguments?.getInt("time") ?: 0,
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = "${Routes.ADD_EDIT}?id={id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L })
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    AddEditScreen(medicineId = id, onSaved = { navController.popBackStack() })
                }

                composable(
                    route = "${Routes.HISTORY}?day={day}",
                    arguments = listOf(navArgument("day") {
                        type = NavType.LongType
                        defaultValue = ScheduleUtil.today().toEpochDay()
                    })
                ) { entry ->
                    val day = entry.arguments?.getLong("day") ?: ScheduleUtil.today().toEpochDay()
                    HistoryScreen(epochDay = day)
                }

                composable(
                    route = "${Routes.MEDICINE_HISTORY}/{id}",
                    arguments = listOf(navArgument("id") { type = NavType.LongType })
                ) { entry ->
                    val id = entry.arguments?.getLong("id") ?: 0L
                    MedicineHistoryScreen(medicineId = id)
                }
            }
        }
    }
}

/**
 * Floating pill navigation: Home · Schedule · (+) · Medicines · Stats · Settings.
 * The center (+) is an elevated circular action.
 */
@Composable
private fun FloatingBottomNav(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit
) {
    val left = listOf(BottomDest.HOME, BottomDest.SCHEDULE)
    val right = listOf(BottomDest.MEDICINES, BottomDest.STATS, BottomDest.SETTINGS)
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(28.dp))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            left.forEach { NavPill(it, currentRoute, Modifier.weight(1f), onSelect) }
            Spacer(Modifier.width(56.dp)) // gap for elevated (+)
            right.forEach { NavPill(it, currentRoute, Modifier.weight(1f), onSelect) }
        }
        // Elevated center (+)
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 0.dp)
                .size(58.dp)
                .background(
                    Brush.linearGradient(listOf(Indigo, AccentPurple)),
                    CircleShape
                )
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, "Add medicine", tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun NavPill(
    dest: BottomDest,
    currentRoute: String?,
    modifier: Modifier,
    onSelect: (String) -> Unit
) {
    val selected = currentRoute == dest.route
    Column(
        modifier
            .clickable { onSelect(dest.route) }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            dest.icon,
            dest.label,
            tint = if (selected) AccentPurple else DarkOnSurfaceMuted,
            modifier = Modifier.size(22.dp)
        )
        Text(
            dest.label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) AccentPurple else DarkOnSurfaceMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            fontSize = 10.sp
        )
    }
}

private fun topTitle(route: String?): String = when {
    route?.startsWith(Routes.ADD_EDIT) == true -> "Medicine"
    route?.startsWith(Routes.HISTORY) == true -> "History"
    route?.startsWith(Routes.MEDICINE_HISTORY) == true -> "Medicine history"
    route?.startsWith(Routes.EVENT_DETAIL) == true -> "Dose details"
    route == Routes.TREATMENT -> "My TB Treatment"
    route == Routes.DEVICES -> "Authorized devices"
    route == Routes.MONITOR -> "Monitor"
    route == Routes.TIMELINE -> "Treatment timeline"
    route == Routes.IMPORT_HISTORY -> "Import history"
    route == Routes.CLOUD_SYNC -> "Cloud sync"
    route?.startsWith(Routes.PHASE_PREVIEW) == true -> "Schedule preview"
    else -> "TB MedTrack"
}
