package com.riyadm.wakenudge.ui.ring

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.engine.WakeCheckController
import com.riyadm.wakenudge.engine.notify.Actions
import com.riyadm.wakenudge.ui.components.WavyCircularProgress
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** "Still awake?" prompt: one big button and a countdown. Unanswered, the alarm rings again when time runs out. */
@AndroidEntryPoint
class WakeCheckActivity : ComponentActivity() {
    @Inject lateinit var wakeChecks: WakeCheckController
    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val alarmId = intent.getLongExtra(Actions.EXTRA_ID, 0)
        val deadline = intent.getLongExtra(EXTRA_DEADLINE, System.currentTimeMillis() + 60_000)
        setContent {
            val s by settingsRepo.settings.collectAsState(initial = AppSettings())
            WakeNudgeTheme(s.themeMode, s.dynamicColor, s.haptics) {
                WakeCheckScreen(
                    deadlineMillis = deadline,
                    onAwake = { lifecycleScope.launch { wakeChecks.answer(alarmId); finishAndRemoveTask() } },
                    onCancel = { lifecycleScope.launch { wakeChecks.abandon(alarmId, WakeCheckStatus.CANCELLED); finishAndRemoveTask() } },
                    onExpired = { finishAndRemoveTask() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_DEADLINE = "deadline"
    }
}

@Composable
private fun WakeCheckScreen(deadlineMillis: Long, onAwake: () -> Unit, onCancel: () -> Unit, onExpired: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalWakeHaptics.current
    val total = remember { (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(1_000) }
    val remainingMs by produceState(total) {
        while (true) {
            value = (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0)
            if (value == 0L) break
            delay(200)
        }
    }
    LaunchedEffect(remainingMs) { if (remainingMs == 0L) onExpired() }
    Box(Modifier.fillMaxSize().background(scheme.tertiaryContainer).safeDrawingPadding()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.wake_check_screen_title), style = MaterialTheme.typography.displayMedium, color = scheme.onTertiaryContainer, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.wake_check_screen_body),
                style = MaterialTheme.typography.bodyLarge, color = scheme.onTertiaryContainer,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 32.dp),
            )
            Box(contentAlignment = Alignment.Center) {
                WavyCircularProgress(progress = (remainingMs.toFloat() / total).coerceIn(0f, 1f), size = 220.dp, color = scheme.tertiary, trackColor = scheme.surface.copy(alpha = 0.4f))
                Text(
                    "${(remainingMs + 999) / 1000}", style = ClockType.Readout, color = scheme.onTertiaryContainer,
                )
            }
            Button(
                onClick = { haptics.confirm(); onAwake() },
                modifier = Modifier.padding(top = 40.dp).fillMaxWidth().height(80.dp),
            ) { Text(stringResource(R.string.action_im_awake), style = MaterialTheme.typography.titleLarge) }
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.action_cancel_check)) }
        }
    }
}
