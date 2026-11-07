package com.riyadm.wakenudge.engine.policy

/** What the phone looks like this second, as far as bringing the ring screen back is concerned. Elapsed-clock times. */
data class ResurfaceInput(
    val now: Long,
    /** A ring is on (not a silent sunrise glow). */
    val ringing: Boolean,
    /** The alarm wants its screen back at all (`returnToAlarm`). */
    val returnToAlarm: Boolean,
    /** The alarm wants it back at once, not only an alert (`forceReturn`). */
    val forceReturn: Boolean,
    /** The ring screen is really in front: on top, with input, not beside another app or behind the Overview (RingScreenFront). */
    val uiInFront: Boolean,
    val screenOn: Boolean,
    val keyguardLocked: Boolean,
    /** "Display over other apps" is granted, which background starts of the ring screen need. */
    val canDrawOverlays: Boolean,
    val emergencyPaused: Boolean,
    /** A phone call is going on or ringing in. */
    val callActive: Boolean,
    /** A mission opened a permission dialog or a settings screen a moment ago. */
    val systemDialogExpected: Boolean,
)

/** What to do this second. */
enum class ResurfaceAction {
    NONE,

    /** Start the ring screen again (needs "Display over other apps"). */
    PULL_BACK,

    /** Post the ring notification again as a heads-up, with no full-screen launch: it cannot cover anything. */
    ALERT,

    /** The screen went off: light it and post the full-screen ring again (and pull back if allowed). */
    WAKE,
}

/**
 * When the ring screen comes back (D9, D10). The ring service asks once a second and on screen-off; this decides, pure, on
 * the elapsed clock, so every case is tested with a fake clock.
 *
 * - Unlocked and left (Home, Recents, a swiped card, Clear all, split screen, another app): with [ResurfaceInput.forceReturn]
 *   and the overlay permission the screen is started again, 1 s after leaving, then with a backoff of 1, 1, 5 and 10 s that
 *   resets after a minute on the ring screen. A ring that never had a screen gets it the same way, and that first start
 *   does not count towards the backoff. Without the permission, or without forceReturn, only a heads-up every 20 s.
 * - Never over an active or incoming call, an Emergency pause, or a permission dialog a mission opened.
 * - Locked: only a heads-up, never a full-screen launch, so the lock screen's Emergency call is never covered.
 * - Screen off with the power button: lit again after 3 s. Three screen-offs within 6 s look like an emergency SOS: then
 *   nothing at all for 60 s.
 */
class ResurfacePolicy {
    private var leftAt = -1L
    private var inFrontSince = -1L
    private var streak = 0
    private var everInFront = false
    private var firstShowUsed = false
    private var lastAlertAt = Long.MIN_VALUE / 2
    private var lastWakeAt = Long.MIN_VALUE / 2
    private var screenOffAt = -1L
    private var holdOffUntil = Long.MIN_VALUE / 2
    private val screenOffs = ArrayDeque<Long>()

    /** The screen turned off at [now] (the system broadcast). */
    fun onScreenOff(now: Long) {
        screenOffAt = now
        screenOffs.addLast(now)
        while (screenOffs.isNotEmpty() && now - screenOffs.first() > SOS_WINDOW_MS) screenOffs.removeFirst()
        if (screenOffs.size >= SOS_PRESSES) holdOffUntil = now + SOS_HOLD_OFF_MS
    }

    /** How many times in a row it pulled back without the user staying; what the backoff counts. */
    val pullBackStreak: Int get() = streak

    fun decide(input: ResurfaceInput): ResurfaceAction {
        val now = input.now
        if (!input.ringing || !input.returnToAlarm) {
            leftAt = -1
            return ResurfaceAction.NONE
        }
        if (input.uiInFront) {
            everInFront = true
            if (inFrontSince < 0) inFrontSince = now
            if (now - inFrontSince >= STAY_RESETS_MS) streak = 0
            leftAt = -1
            return ResurfaceAction.NONE
        }
        inFrontSince = -1
        if (leftAt < 0) leftAt = now
        // Whatever else: nothing covers a call, an Emergency pause, a permission dialog, or an SOS in progress.
        if (input.callActive || input.emergencyPaused || input.systemDialogExpected || now < holdOffUntil) return ResurfaceAction.NONE

        if (!input.screenOn) {
            val offSince = if (screenOffAt >= 0) screenOffAt else leftAt
            if (now - offSince >= SCREEN_OFF_WAKE_MS && now - lastWakeAt >= WAKE_REPEAT_MS) {
                lastWakeAt = now
                return ResurfaceAction.WAKE
            }
            return ResurfaceAction.NONE
        }
        if (!input.keyguardLocked && input.forceReturn && input.canDrawOverlays) {
            if (now - leftAt >= backoffMs(streak)) {
                // Showing the screen of a ring that never had one (the phone was in use when it went off) is not the user
                // leaving it: that first start does not count towards the backoff. Every start after it does.
                if (everInFront || firstShowUsed) streak++ else firstShowUsed = true
                leftAt = now
                return ResurfaceAction.PULL_BACK
            }
            return ResurfaceAction.NONE
        }
        if (now - leftAt >= ALERT_EVERY_MS && now - lastAlertAt >= ALERT_EVERY_MS) {
            lastAlertAt = now
            return ResurfaceAction.ALERT
        }
        return ResurfaceAction.NONE
    }

    companion object {
        /** Before the first pull-back after leaving, then before each following one: 1, 1, 1, 5, then 10 s. */
        fun backoffMs(streak: Int): Long = when {
            streak < 3 -> 1_000L
            streak == 3 -> 5_000L
            else -> 10_000L
        }

        /** A minute on the ring screen starts the backoff over. */
        const val STAY_RESETS_MS = 60_000L
        const val ALERT_EVERY_MS = 20_000L
        const val SCREEN_OFF_WAKE_MS = 3_000L
        const val WAKE_REPEAT_MS = 10_000L
        const val SOS_PRESSES = 3
        const val SOS_WINDOW_MS = 6_000L
        const val SOS_HOLD_OFF_MS = 60_000L

        /** How long a permission dialog or settings screen a mission opened keeps the ring screen from coming back. */
        const val SYSTEM_DIALOG_MS = 30_000L
    }
}
