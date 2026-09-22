package com.tbmedtrack.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tbmedtrack.app.ui.calendar.CalendarScreen
import com.tbmedtrack.app.ui.history.HistoryScreen
import com.tbmedtrack.app.ui.home.HomeScreen
import com.tbmedtrack.app.ui.medicines.AddEditScreen
import com.tbmedtrack.app.ui.medicines.MedicineHistoryScreen
import com.tbmedtrack.app.ui.medicines.MedicinesScreen
import com.tbmedtrack.app.ui.navigation.BottomDest
import com.tbmedtrack.app.ui.navigation.Routes
import com.tbmedtrack.app.ui.settings.SettingsScreen
import com.tbmedtrack.app.ui.stats.StatsScreen
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
        currentRoute == Routes.IMPORT_HISTORY

    Scaffold(
        topBar = {
            if (isDetail) {
                TopAppBar(
                    title = { Text(topTitle(currentRoute)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    BottomDest.entries.forEach { dest ->
                        val selected = backStackEntry?.destination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, dest.label) },
                            label = { Text(dest.label) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == Routes.HOME || currentRoute == Routes.MEDICINES) {
                FloatingActionButton(onClick = { navController.navigate("${Routes.ADD_EDIT}?id=0") }) {
                    Icon(Icons.Filled.Add, "Add medicine")
                }
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
                    onOpenTreatment = { navController.navigate(Routes.TREATMENT) }
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
                    onImportHistory = { navController.navigate(Routes.IMPORT_HISTORY) }
                )
            }
            composable(Routes.TREATMENT) {
                TreatmentScreen(onOpenTimeline = { navController.navigate(Routes.TIMELINE) })
            }
            composable(Routes.DEVICES) { com.tbmedtrack.app.ui.devices.DevicesScreen() }
            composable(Routes.MONITOR) { com.tbmedtrack.app.ui.monitor.MonitorScreen() }
            composable(Routes.TIMELINE) { com.tbmedtrack.app.ui.timeline.TimelineScreen() }
            composable(Routes.IMPORT_HISTORY) {
                com.tbmedtrack.app.ui.setup.SetupScreen(onDone = { navController.popBackStack() })
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

private fun topTitle(route: String?): String = when {
    route?.startsWith(Routes.ADD_EDIT) == true -> "Medicine"
    route?.startsWith(Routes.HISTORY) == true -> "History"
    route?.startsWith(Routes.MEDICINE_HISTORY) == true -> "Medicine history"
    route == Routes.TREATMENT -> "My TB Treatment"
    route == Routes.DEVICES -> "Authorized devices"
    route == Routes.MONITOR -> "Monitor"
    route == Routes.TIMELINE -> "Treatment timeline"
    route == Routes.IMPORT_HISTORY -> "Import history"
    else -> "TB MedTrack"
}
