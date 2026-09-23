package com.tbmedtrack.app.reminder

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.first

/**
 * Full-screen WARNING-ONLY critical alert shown (over the lock screen where the OS allows)
 * when a required dose has not been recorded. It has NO completion button — recording a dose
 * happens only on the normal medication screen (spec #94-96). Dismissing/leaving does NOT
 * record the medicine as taken; the next configured alarm keeps firing until it is recorded.
 *
 * The pulsing red uses a slow (~0.5 Hz) fade that is well below seizure-risk flash rates,
 * and it is disabled entirely when the user enables "reduce motion".
 */
class CriticalAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val epochDay = intent.getLongExtra(ReminderKeys.EXTRA_EPOCH_DAY, ScheduleUtil.today().toEpochDay())
        val timeMinutes = intent.getIntExtra(ReminderKeys.EXTRA_TIME_MINUTES, 10 * 60)
        val scheduledMillis = intent.getLongExtra(
            ReminderKeys.EXTRA_SCHEDULED_MILLIS,
            ScheduleUtil.toEpochMillis(ScheduleUtil.dateFromEpochDay(epochDay), timeMinutes)
        )

        setContent {
            val repo = ServiceLocator.medRepository(this)
            val settingsRepo = ServiceLocator.settingsRepository(this)

            val reduceMotion by produceState(initialValue = false) {
                value = runCatching { settingsRepo.settings.first().reduceMotion }.getOrDefault(false)
            }
            val names by produceState(initialValue = emptyList<String>(), epochDay, timeMinutes) {
                val date = ScheduleUtil.dateFromEpochDay(epochDay)
                value = repo.getDosesForDay(date)
                    .filter { it.timeMinutes == timeMinutes }
                    .map { it.medicineName }
            }

            com.tbmedtrack.app.ui.theme.TBMedTrackTheme {
                CriticalAlertScreen(
                    timeMinutes = timeMinutes,
                    medicineNames = names,
                    reduceMotion = reduceMotion,
                    onOpenApp = { openAppAndFinish() },
                    onClose = { finish() }
                )
            }
        }
    }

    /**
     * Opens the main app so the user can record the dose on the normal medication interface.
     * This screen NEVER records the dose itself (see spec #94-96).
     */
    private fun openAppAndFinish() {
        startActivity(
            android.content.Intent(this, com.tbmedtrack.app.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}

/**
 * WARNING-ONLY alert. It intentionally has NO "Medicine Taken" button (spec #94-96): recording
 * a dose can only be done from the normal medication interface. This screen just alerts, shows
 * the NOT RECORDED status, and lets the user open the app or close the alert. Closing does not
 * record anything — the next configured alarm will fire until the dose is recorded elsewhere.
 */
@Composable
private fun CriticalAlertScreen(
    timeMinutes: Int,
    medicineNames: List<String>,
    reduceMotion: Boolean,
    onOpenApp: () -> Unit,
    onClose: () -> Unit
) {
    val baseRed = Color(0xFFB3120C)
    val brightRed = Color(0xFFE53935)
    val nowLabel = ScheduleUtil.formatTime(
        java.time.LocalTime.now(ScheduleUtil.zone()).let { it.hour * 60 + it.minute }
    )

    val pulseAlpha: Float = if (reduceMotion) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "pulse")
        val a by transition.animateFloat(
            initialValue = 0.72f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )
        a
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(baseRed)
            .alpha(if (reduceMotion) 1f else pulseAlpha)
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(24.dp))
        Text("🚨", fontSize = 72.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "MEDICATION ALERT",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your scheduled medication has not been recorded.",
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Text("Scheduled: ${ScheduleUtil.formatTime(timeMinutes)}",
            color = Color.White, fontSize = 16.sp)
        Text("Current: $nowLabel", color = Color.White, fontSize = 16.sp)
        Spacer(Modifier.height(20.dp))
        if (medicineNames.isNotEmpty()) {
            Text("Today's combination: ${medicineNames.size} medicines",
                color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            medicineNames.forEach { name ->
                Text("• $name", color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(2.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("STATUS", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        Text("⚠ NOT RECORDED", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)

        Spacer(Modifier.height(28.dp))
        // "Open app" only navigates — it does NOT record the dose.
        Button(
            onClick = onOpenApp,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = brightRed),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("OPEN APP TO RECORD", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        Spacer(Modifier.height(10.dp))
        androidx.compose.material3.TextButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Close", color = Color.White) }
        Spacer(Modifier.height(12.dp))
        Text(
            "Closing this alert does not record your medicine. Record it from the app's medication screen.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}
