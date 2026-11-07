package com.riyadm.wakenudge.ui.ring

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.model.Difficulty
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.engine.WakeCheckController
import com.riyadm.wakenudge.engine.WakeProof
import com.riyadm.wakenudge.engine.audio.SoundPreviewer
import com.riyadm.wakenudge.missions.MissionHost
import com.riyadm.wakenudge.engine.notify.Actions
import com.riyadm.wakenudge.ui.components.WavyCircularProgress
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Still awake?" prompt: one big button and a countdown. Unanswered, the alarm rings again when time runs out. On an
 * alarm that asks for proof there is no button and no Cancel: one round of Focus Tap answers it, and a tone plays on the
 * alarm stream while the screen waits, so it is heard on a phone set to silent.
 */
@AndroidEntryPoint
class WakeCheckActivity : ComponentActivity() {
    @Inject lateinit var wakeChecks: WakeCheckController
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var previewer: SoundPreviewer

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

        prompt = promptOf(intent)
        setContent {
            val s by settingsRepo.settings.collectAsState(initial = AppSettings())
            val current = prompt
            WakeNudgeTheme(s.themeMode, s.dynamicColor, s.haptics) {
                // Keyed by the prompt: a second alarm's check arriving while this screen is open starts afresh.
                key(current) {
                    if (current.proof) {
                        LaunchedEffect(Unit) {
                            while (true) {
                                previewer.playBundled(PROMPT_TONE, toggle = false)
                                delay(PROMPT_REPEAT_MS)
                            }
                        }
                        val sums = s.noTimedMissions
                        ProofCheckScreen(
                            deadlineMillis = current.deadline,
                            sums = sums,
                            onProof = { finishAfter { wakeChecks.answer(current.alarmId, if (sums) WakeProof.SUMS else WakeProof.FOCUS_TAP) } },
                            onExpired = { finishAndRemoveTask() },
                        )
                    } else {
                        WakeCheckScreen(
                            deadlineMillis = current.deadline,
                            onAwake = { finishAfter { wakeChecks.answer(current.alarmId) } },
                            onCancel = { finishAfter { wakeChecks.cancelByUser(current.alarmId) } },
                            onExpired = { finishAndRemoveTask() },
                        )
                    }
                }
            }
        }
    }

    /** This screen is single-task: another alarm's "Still awake?" is delivered here, and must be the one answered. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        prompt = promptOf(intent)
    }

    /** The answer is stored, then the screen closes, even if storing it fails (the check then simply runs its course). */
    private fun finishAfter(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                block()
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.w("WakeCheck", "could not record the answer", t)
            } finally {
                finishAndRemoveTask()
            }
        }
    }

    override fun onDestroy() {
        previewer.stop()
        super.onDestroy()
    }

    private data class Prompt(val alarmId: Long, val deadline: Long, val proof: Boolean)

    private var prompt by mutableStateOf(Prompt(0, 0, false))

    private fun promptOf(intent: Intent) = Prompt(
        intent.getLongExtra(Actions.EXTRA_ID, 0),
        intent.getLongExtra(EXTRA_DEADLINE, System.currentTimeMillis() + 60_000),
        intent.getBooleanExtra(EXTRA_PROOF, false),
    )

    companion object {
        const val EXTRA_DEADLINE = "deadline"

        /** The alarm asks for proof: the screen shows Focus Tap instead of its button. The engine checks again on answering. */
        const val EXTRA_PROOF = "proof"
        private const val PROMPT_TONE = "beacon"
        private const val PROMPT_REPEAT_MS = 15_000L
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
    // Scrolls and shrinks the countdown in landscape or with a large font, so "I'm awake" is always reachable.
    BoxWithConstraints(Modifier.fillMaxSize().background(scheme.tertiaryContainer).safeDrawingPadding()) {
        val short = maxHeight < 560.dp
        val ring = minOf(220.dp, maxHeight * 0.3f).coerceAtLeast(120.dp)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.wake_check_screen_title),
                style = if (short) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium,
                color = scheme.onTertiaryContainer, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.wake_check_screen_body),
                style = MaterialTheme.typography.bodyLarge, color = scheme.onTertiaryContainer,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = if (short) 16.dp else 32.dp),
            )
            Box(contentAlignment = Alignment.Center) {
                WavyCircularProgress(progress = (remainingMs.toFloat() / total).coerceIn(0f, 1f), size = ring, color = scheme.tertiary, trackColor = scheme.surface.copy(alpha = 0.4f))
                Text(
                    "${(remainingMs + 999) / 1000}", style = ClockType.Readout, color = scheme.onTertiaryContainer,
                )
            }
            Button(
                onClick = { haptics.confirm(); onAwake() },
                modifier = Modifier.padding(top = if (short) 16.dp else 40.dp).fillMaxWidth().heightIn(min = 80.dp),
            ) { Text(stringResource(R.string.action_im_awake), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) }
            TextButton(onClick = onCancel, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.action_cancel_check)) }
        }
    }
}

/**
 * The check that needs proof: its countdown, and one round of Focus Tap, which works with a screen reader too. With
 * [sums] ("I can't do timed or physical missions") it asks for three sums instead: nothing timed.
 */
@Composable
internal fun ProofCheckScreen(deadlineMillis: Long, sums: Boolean, onProof: () -> Unit, onExpired: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val remainingMs by produceState((deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0)) {
        while (true) {
            value = (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0)
            if (value == 0L) break
            delay(200)
        }
    }
    LaunchedEffect(remainingMs) { if (remainingMs == 0L) onExpired() }
    val round = remember(sums) {
        listOf(if (sums) MissionConfig(MissionType.MATH, Difficulty.MEDIUM, count = SUMS_FOR_PROOF) else MissionConfig(MissionType.FOCUS_TAP, Difficulty.MEDIUM, count = 1))
    }
    Column(Modifier.fillMaxSize().background(scheme.tertiaryContainer).safeDrawingPadding().padding(16.dp)) {
        Text(
            stringResource(R.string.wake_check_screen_title), style = MaterialTheme.typography.headlineMedium,
            color = scheme.onTertiaryContainer, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(if (sums) R.string.wake_check_proof_body_sums else R.string.wake_check_proof_body) + "  " + "${(remainingMs + 999) / 1000}",
            style = MaterialTheme.typography.bodyLarge, color = scheme.onTertiaryContainer,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
        )
        MissionHost(round, Modifier.weight(1f).fillMaxWidth(), onComplete = onProof)
    }
}

/** Sums that stand in for a round of Focus Tap: a few, at the backup mission's level, so it proves being up, not cleverness. */
private const val SUMS_FOR_PROOF = 3
