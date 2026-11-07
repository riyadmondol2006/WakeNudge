package com.riyadm.wakenudge.engine.policy

/**
 * Whether the ring screen is really in front, from what the screen and the system tell the app. Pure, on the elapsed
 * clock, so every case is tested on the JVM.
 *
 * Resumed is not enough. Since Android 10 an activity can be resumed and not on top: beside another app in split screen, or
 * started while the Overview shows. And on a Pixel with gesture navigation the Overview opened over the ring screen leaves it
 * resumed and on top as far as the app is told (the "live tile"): only its window loses input to the launcher. The system
 * does announce the Overview (`ACTION_CLOSE_SYSTEM_DIALOGS` with reason `recentapps`) for the Recents key and the swipe-up
 * gesture alike, so a window that loses input around that announcement is behind the Overview.
 *
 * A window can lose input for other reasons that are not leaving: the ring screen's own dialogs (Emergency, a way-out
 * confirmation), the notification shade, the power menu with its Emergency button. Without the Overview announcement those
 * still count as in front, so the ring screen is never pulled back over them.
 */
class RingScreenFront {
    var resumed = false
        private set
    var topResumed = false
        private set
    var inMultiWindow = false
        private set
    var focused = false
        private set
    private var focusLostAt = NEVER / 2
    private var recentsAt = NEVER

    /** Another ring screen took over (a new task after a swiped card): it starts with nothing known about it. */
    fun onNewScreen() {
        resumed = false
        topResumed = false
        inMultiWindow = false
        focused = false
        recentsAt = NEVER
    }

    fun onResumed(resumed: Boolean, inMultiWindow: Boolean) {
        this.resumed = resumed
        this.inMultiWindow = inMultiWindow
        if (!resumed) topResumed = false
    }

    fun onTopResumed(top: Boolean) {
        topResumed = top
    }

    fun onFocus(focused: Boolean, now: Long) {
        if (this.focused && !focused) focusLostAt = now
        this.focused = focused
    }

    /** The system said the Overview opened (`recentapps`). */
    fun onRecentsOpened(now: Long) {
        recentsAt = now
    }

    /** Lost input to the Overview: unfocused, and the Overview was announced shortly before the window lost input, or since. */
    val behindOverview: Boolean get() = !focused && recentsAt >= focusLostAt - RECENTS_HINT_MS

    val inFront: Boolean get() = resumed && topResumed && !inMultiWindow && !behindOverview

    companion object {
        /** The announcement and the loss of input arrive within a few hundred ms of each other, in either order. */
        const val RECENTS_HINT_MS = 2_000L

        /** Before anything happened; far enough back that no announcement counts against it. */
        private const val NEVER = Long.MIN_VALUE / 2
    }
}
