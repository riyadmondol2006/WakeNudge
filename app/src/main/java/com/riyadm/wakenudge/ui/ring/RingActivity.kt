package com.riyadm.wakenudge.ui.ring

import android.app.KeyguardManager
import android.content.Intent
import android.content.res.Configuration
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.VolumeKeyAction
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.engine.RingController
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingTarget
import com.riyadm.wakenudge.engine.RingSessionStore
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.engine.policy.ResurfacePolicy
import com.riyadm.wakenudge.engine.policy.RingPolicy
import com.riyadm.wakenudge.engine.policy.RingProof
import com.riyadm.wakenudge.engine.policy.WayOut
import com.riyadm.wakenudge.missions.ui.LocalExpectSystemDialog
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
    @Inject lateinit var time: TimeSource

    @Volatile private var settings: AppSettings = AppSettings()

    /** Set just before a dismissal from this screen: whether a still-awake check follows. Read when the session ends. */
    @Volatile private var dismissing: Boolean? = null
    private val youreUp = mutableStateOf<Boolean?>(null)

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
                    if (s == null && seen) {
                        // Dismissed from here: a moment of "You're up", naming the check to come, then the screen goes.
                        dismissing?.let { checkFollows ->
                            youreUp.value = checkFollows
                            kotlinx.coroutines.delay(YOURE_UP_MS)
                        }
                        finishAndRemoveTask()
                    }
                    if (s != null) {
                        applyBrightness(s.phase, s.startedAt, s.alarm.sunrise.leadMinutes, s.alarm.antiOversleep.fullBrightness)
                        applySecure(s)
                    }
                }
            }
        }
        // Sunrise: the display brightens every second through the lead time, not only when the session changes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    store.current?.takeIf { it.phase == RingPhase.SUNRISE }?.let { applyBrightness(it.phase, it.startedAt, it.alarm.sunrise.leadMinutes, false) }
                    kotlinx.coroutines.delay(1_000)
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
                youreUp.value?.takeIf { session == null }?.let { YoureUp(checkFollows = it) }
                // A permission dialog or settings screen a mission opens is the user's, not a reason to pull the alarm back.
                CompositionLocalProvider(LocalExpectSystemDialog provides { store.expectSystemDialog(time.elapsedMillis() + ResurfacePolicy.SYSTEM_DIALOG_MS) }) {
                session?.let { current ->
                    RingScreen(
                        session = current,
                        settings = s,
                        actions = RingActions(
                            // Each button acts on the ring it was drawn for, never on one that took over a moment ago.
                            onSnooze = { proof -> lifecycleScope.launch { controller.snooze(RingTarget.of(current), proof) } },
                            onDismiss = { proof ->
                                lifecycleScope.launch {
                                    dismissing = current.alarm.wakeCheck.enabled && !current.isTest && !current.isTimer
                                    if (!controller.dismiss(RingTarget.of(current), proof)) dismissing = null
                                }
                            },
                            onEmergencyStop = { proof -> lifecycleScope.launch { controller.emergencyStop(RingTarget.of(current), proof) } },
                            onAddMinute = { lifecycleScope.launch { controller.addMinuteToRingingTimer() } },
                            onHide = { finish() },
                            onInteraction = controller::noteInteraction,
                            onMissionProgress = controller::noteMissionDone,
                            onWayOut = { proof -> lifecycleScope.launch { controller.wayOut(RingTarget.of(current), proof) } },
                            onWayOutStep = controller::noteWayOutStep,
                            onEmergencyCall = { emergencyCall(this, controller, RingTarget.of(current)) },
                            onFallbackUsed = controller::noteFallbackUsed,
                            onMissionInput = controller::noteProgress,
                            onMute = { controller.muteForMission(RingTarget.of(current), RingProof.Gesture) },
                        ),
                        missionContent = { missions, onInteraction, onProgress, onComplete, first ->
                            RingMissionContent(missions, onInteraction, onProgress, onComplete, first)
                        },
                    )
                }
                }
            }
        }
    }

    /**
     * Screenshots of an alarm whose way out opens late (Hard and above) are blocked, so a screen-reading assistant cannot
     * be asked to solve its sums. Screen readers are not affected.
     */
    private fun applySecure(s: com.riyadm.wakenudge.engine.RingSession) {
        val secure = (RingPolicy.wayOut(s) as? WayOut.Chain)?.let { it.delayMinutes > 0 } == true
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    companion object {
        private const val YOURE_UP_MS = 1_800L

        /**
         * Emergency, "Call or use phone": the alarm pauses, then the phone app opens, or on a locked phone the unlock
         * screen with its Emergency call button (an app may not open the emergency dialer itself). The phone is opened
         * whatever the pause says: nothing may stand between someone and a call.
         */
        fun emergencyCall(activity: android.app.Activity, controller: RingController, target: RingTarget) {
            controller.pauseForEmergency(target, RingProof.Gesture)
            val dial = { runCatching { activity.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            val keyguard = activity.getSystemService(KeyguardManager::class.java)
            if (keyguard?.isKeyguardLocked == true) {
                keyguard.requestDismissKeyguard(
                    activity,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() { dial() }
                    },
                )
            } else {
                dial()
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

    /**
     * Sunrise mode: the display itself brightens gradually. During the ring it is at full brightness when the alarm asks for
     * it ([fullBrightness], Normal and above), so a mission can be read in a dark room; otherwise it follows the phone. Only
     * this window is changed, never the system setting, and it goes back when the window does.
     */
    private fun applyBrightness(phase: RingPhase, startedAt: Long, leadMinutes: Int, fullBrightness: Boolean) {
        val attrs = window.attributes
        attrs.screenBrightness = when {
            phase == RingPhase.SUNRISE -> {
                val total = (leadMinutes * 60_000L).coerceAtLeast(1)
                (0.04f + 0.96f * ((System.currentTimeMillis() - startedAt).toFloat() / total)).coerceIn(0.04f, 1f)
            }
            fullBrightness -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = attrs
    }

    override fun onStart() {
        super.onStart()
        store.setUiVisible(true)
    }

    override fun onResume() {
        super.onResume()
        store.setUiResumed(true, isInMultiWindowMode)
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        store.setUiResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), isInMultiWindowMode)
    }

    override fun onPause() {
        store.setUiResumed(false, isInMultiWindowMode)
        super.onPause()
    }

    override fun onStop() {
        store.setUiVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        store.setUiVisible(false)
        store.setUiResumed(false, false)
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
        // During the silent sunrise glow nothing is ringing yet: the keys are ordinary volume keys.
        if (session.phase != RingPhase.RINGING) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true
        controller.noteInteraction()
        // Each alarm says what its keys do; on Normal and above they do nothing, and the volume itself is held anyway.
        return when (session.alarm.antiOversleep.volumeKeyAction) {
            VolumeKeyAction.VOLUME -> super.onKeyDown(keyCode, event)
            VolumeKeyAction.NOTHING -> true
            VolumeKeyAction.SILENCE_TEMPORARILY -> { controller.silenceTemporarily(); true }
            // A key proves nothing, so the controller only lets these through for an alarm with no mission or hold to bypass.
            VolumeKeyAction.SNOOZE -> {
                lifecycleScope.launch { controller.snooze(RingTarget.of(session)) }
                true
            }
            VolumeKeyAction.DISMISS -> {
                lifecycleScope.launch { controller.dismiss(RingTarget.of(session)) }
                true
            }
        }
    }
}
