package com.tbmedtrack.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tbmedtrack.app.data.settings.ThemeMode
import com.tbmedtrack.app.reminder.NotificationHelper
import com.tbmedtrack.app.ui.TbMedApp
import com.tbmedtrack.app.ui.onboarding.OnboardingScreen
import com.tbmedtrack.app.ui.theme.TBMedTrackTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settingsRepo = ServiceLocator.settingsRepository(this)
        val scheduler = ServiceLocator.alarmScheduler(this)

        val notifPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* result handled by UI state on next resume */ }

        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(
                initialValue = com.tbmedtrack.app.data.settings.AppSettings()
            )
            val scope = rememberCoroutineScope()

            TBMedTrackTheme(themeMode = settings.themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isSecondary = settings.deviceRole ==
                        com.tbmedtrack.app.data.settings.DeviceRoleValue.SECONDARY
                    when {
                        !settings.onboardingDone -> {
                            OnboardingScreen(onFinished = {
                                scope.launch { settingsRepo.setOnboardingDone(true) }
                                requestNotificationPermission(notifPermissionLauncher)
                            })
                        }
                        // First-launch setup wizard: permissions + device-type selection. Runs
                        // until the user picks Main (or connects a Secondary).
                        !settings.setupWizardDone -> {
                            com.tbmedtrack.app.ui.wizard.SetupWizardScreen(
                                onRequestNotificationPermission = {
                                    requestNotificationPermission(notifPermissionLauncher)
                                },
                                onMainChosen = { /* state flips via setupWizardDone */ },
                                onSecondaryConnected = { /* state flips via setupWizardDone */ }
                            )
                        }
                        // SECONDARY device: monitoring-only view (no reminders/alarms UI).
                        isSecondary -> {
                            LaunchedEffect(Unit) {
                                ServiceLocator.syncManager(this@MainActivity).reconfigure()
                                ServiceLocator.syncManager(this@MainActivity).syncNow()
                            }
                            com.tbmedtrack.app.ui.monitor.MonitorScreen()
                        }
                        // MAIN device first-setup (treatment start + optional history import).
                        settings.trackingStartDay == 0L -> {
                            com.tbmedtrack.app.ui.setup.SetupScreen(onDone = { /* state flips via settings */ })
                        }
                        else -> {
                            LaunchedEffect(Unit) {
                                requestNotificationPermission(notifPermissionLauncher)
                                scheduler.rescheduleAll()
                            }
                            TbMedApp()
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission(
        launcher: androidx.activity.result.ActivityResultLauncher<String>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!NotificationHelper.hasNotificationPermission(this)) {
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
