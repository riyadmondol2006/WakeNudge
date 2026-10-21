package com.riyadm.wakenudge.ui.ring

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.VolumeKeyAction
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.domain.scheduling.SnoozePolicy
import com.riyadm.wakenudge.engine.RingController
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSessionStore
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The alarm screen. Shown over the lock screen and turns the display on. It owns no alarm logic: it renders the
 * [RingSessionStore] session and forwards decisions to [RingController]. Back is swallowed while ringing so a stray
 * gesture cannot leave the alarm unattended; the dismiss, snooze and emergency-stop controls are the exits.
 */
@AndroidEntryPoint
class RingActivity : ComponentActivity() {
    @Inject lateinit var controller: RingController
    @Inject lateinit var store: RingSessionStore
    @Inject lateinit var settingsRepo: SettingsRepository

    @Volatile private var settings: AppSettings = AppSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showOverLockScreen()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Deliberately ignored while an alarm is active; with no session there is nothing to protect.
                if (store.current == null) finish() else controller.noteInteraction()
            }
        })

        lifecycleScope.launch {
            settings = settingsRepo.current()
            settingsRepo.settings.collect { settings = it }
        }
        // Close as soon as the session is over (dismissed, snoozed, timed out, replaced).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                var seen = false
                store.session.collect { s ->
                    if (s != null) seen = true
                    if (s == null && seen) finishAndRemoveTask()
                    if (s != null) applyBrightness(s.phase, s.startedAt, s.alarm.sunrise.leadMinutes)
                }
            }
        }
        lifecycleScope.launch {
            // No session shortly after launch means this was a stale notification tap: do not strand the user here.
            kotlinx.coroutines.delay(2_500)
            if (store.current == null) finishAndRemoveTask()
        }

        setContent {
            val s by settingsRepo.settings.collectAsState(initial = settings)
            val session by store.session.collectAsState()
            WakeNudgeTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor, haptics = s.haptics) {
                session?.let { current ->
                    RingScreen(
                        session = current,
                        settings = s,
                        actions = RingActions(
                            onSnooze = { lifecycleScope.launch { controller.snooze() } },
                            onDismiss = { lifecycleScope.launch { controller.dismiss() } },
                            onEmergencyStop = { lifecycleScope.launch { controller.emergencyStop() } },
                            onAddMinute = { lifecycleScope.launch { controller.addMinuteToRingingTimer() } },
                            onHide = { finish() },
                            onInteraction = controller::noteInteraction,
                            onMissionProgress = controller::noteMissionDone,
                        ),
                        missionContent = { missions, onInteraction, onProgress, onComplete ->
                            RingMissionContent(missions, onInteraction, onProgress, onComplete)
                        },
                    )
                }
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON)
    }

    /** Sunrise mode: the display itself brightens gradually; during the ring it is at full brightness. */
    private fun applyBrightness(phase: RingPhase, startedAt: Long, leadMinutes: Int) {
        val attrs = window.attributes
        attrs.screenBrightness = if (phase == RingPhase.SUNRISE) {
            val total = (leadMinutes * 60_000L).coerceAtLeast(1)
            (0.04f + 0.96f * ((System.currentTimeMillis() - startedAt).toFloat() / total)).coerceIn(0.04f, 1f)
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = attrs
    }

    override fun onStart() {
        super.onStart()
        store.setUiVisible(true)
    }

    override fun onStop() {
        store.setUiVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        store.setUiVisible(false)
        val attrs = window.attributes
        attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) controller.noteInteraction()
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return super.onKeyDown(keyCode, event)
        val session = store.current ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true
        controller.noteInteraction()
        val alarm = session.alarm
        return when (settings.volumeKeyAction) {
            VolumeKeyAction.VOLUME -> super.onKeyDown(keyCode, event)
            VolumeKeyAction.NOTHING -> true
            VolumeKeyAction.SILENCE_TEMPORARILY -> { controller.silenceTemporarily(); true }
            VolumeKeyAction.SNOOZE -> {
                // Only a plain snooze: when a mission is required the key must not bypass it.
                if (!session.isTimer && alarm.snoozeMissions.isEmpty() && SnoozePolicy.canSnooze(alarm.snooze, session.snoozeCount)) {
                    lifecycleScope.launch { controller.snooze() }
                }
                true
            }
            VolumeKeyAction.DISMISS -> {
                if (session.isTimer || (!session.hasMissions && alarm.antiOversleep.holdToDismissSeconds == 0)) {
                    lifecycleScope.launch { controller.dismiss() }
                }
                true
            }
        }
    }
}
