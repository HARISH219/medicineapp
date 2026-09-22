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
import androidx.lifecycle.lifecycleScope
import com.tbmedtrack.app.ServiceLocator
import com.tbmedtrack.app.util.ScheduleUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full-screen critical alert shown (over the lock screen where the OS allows) when a
 * required dose has not been recorded. The only completion action is MEDICINE TAKEN,
 * which records the event and cancels the escalation chain. Dismissing/leaving does NOT
 * record the medicine as taken.
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
                    onTaken = { markTakenAndFinish(epochDay, timeMinutes, scheduledMillis) }
                )
            }
        }
    }

    private fun markTakenAndFinish(epochDay: Long, timeMinutes: Int, scheduledMillis: Long) {
        lifecycleScope.launch {
            val repo = ServiceLocator.medRepository(this@CriticalAlertActivity)
            repo.markEventTaken(epochDay, timeMinutes)
            ServiceLocator.criticalAlarmScheduler(this@CriticalAlertActivity)
                .cancelEventChain(epochDay, timeMinutes, scheduledMillis)
            NotificationHelper.cancelCritical(this@CriticalAlertActivity)
            ServiceLocator.syncManager(this@CriticalAlertActivity).queue()
            finish()
        }
    }
}

@Composable
private fun CriticalAlertScreen(
    timeMinutes: Int,
    medicineNames: List<String>,
    reduceMotion: Boolean,
    onTaken: () -> Unit
) {
    val baseRed = Color(0xFFB3120C)
    val brightRed = Color(0xFFE53935)

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
            "MEDICINE NOT RECORDED",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${ScheduleUtil.formatTime(timeMinutes)} dose",
            color = Color.White,
            fontSize = 18.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "Today's combination",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(8.dp))
        medicineNames.forEach { name ->
            Text("💊 $name", color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = onTaken,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = brightRed
            ),
            modifier = Modifier.fillMaxWidth().height(58.dp)
        ) {
            Text("MEDICINE TAKEN", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Only pressing MEDICINE TAKEN records this dose. Closing this screen does not.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}
