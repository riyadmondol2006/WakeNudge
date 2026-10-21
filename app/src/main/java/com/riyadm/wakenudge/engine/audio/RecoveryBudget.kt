package com.riyadm.wakenudge.engine.audio

/**
 * Lets the audio engine repair a broken player a few times in a short window, then tells it to stop being clever and
 * use the emergency beeper: a player that dies the moment it starts must not turn into a tight rebuild loop.
 */
class RecoveryBudget(private val maxRecoveries: Int = 4, private val windowMs: Long = 30_000L) {
    private val times = ArrayDeque<Long>()

    /** Records one recovery at [nowMs]; returns false once there have been more than [maxRecoveries] inside the window. */
    fun tryRecover(nowMs: Long): Boolean {
        while (times.isNotEmpty() && nowMs - times.first() > windowMs) times.removeFirst()
        times.addLast(nowMs)
        return times.size <= maxRecoveries
    }

    fun reset() = times.clear()
}
