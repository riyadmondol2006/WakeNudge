package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.repository.TimerRepository
import com.riyadm.wakenudge.domain.scheduling.SurfaceRefresher
import com.riyadm.wakenudge.engine.notify.Notifier
import javax.inject.Inject
import javax.inject.Singleton

/** Countdown timers: persisted in the database, expiry registered with AlarmManager, live notification with chronometer. */
@Singleton
class TimerController @Inject constructor(
    private val timers: TimerRepository,
    private val pending: PendingAlarms,
    private val notifier: Notifier,
    private val time: TimeSource,
    private val surfaces: SurfaceRefresher,
) {
    suspend fun create(durationMs: Long, label: String, startNow: Boolean): Long {
        val id = timers.save(CountdownTimer(label = label.trim(), durationMs = durationMs.coerceIn(1_000, MAX_MS), remainingMs = durationMs.coerceIn(1_000, MAX_MS)))
        if (startNow) start(id)
        surfaces.refreshAll()
        return id
    }

    suspend fun start(id: Long) {
        val timer = timers.getTimer(id) ?: return
        if (timer.state == TimerState.RUNNING) return
        val remaining = if (timer.remainingMs <= 0) timer.durationMs else timer.remainingMs
        val endsAt = time.nowMillis() + remaining
        val running = timer.copy(state = TimerState.RUNNING, endsAtMillis = endsAt, remainingMs = remaining)
        timers.save(running)
        pending.setExact(TriggerKind.TIMER, id, endsAt)
        notifier.showTimerRunning(running)
        surfaces.refreshAll()
    }

    suspend fun pause(id: Long) {
        val timer = timers.getTimer(id) ?: return
        if (timer.state != TimerState.RUNNING) return
        val remaining = (timer.endsAtMillis - time.nowMillis()).coerceAtLeast(0)
        val paused = timer.copy(state = TimerState.PAUSED, remainingMs = remaining)
        timers.save(paused)
        pending.cancel(TriggerKind.TIMER, id)
        notifier.showTimerRunning(paused)
        surfaces.refreshAll()
    }

    suspend fun reset(id: Long) {
        val timer = timers.getTimer(id) ?: return
        pending.cancel(TriggerKind.TIMER, id)
        notifier.cancelTimer(id)
        timers.save(timer.copy(state = TimerState.IDLE, remainingMs = timer.durationMs, endsAtMillis = 0))
        surfaces.refreshAll()
    }

    suspend fun addMinute(id: Long) {
        val timer = timers.getTimer(id) ?: return
        when (timer.state) {
            TimerState.RUNNING -> {
                val endsAt = timer.endsAtMillis + 60_000
                val updated = timer.copy(endsAtMillis = endsAt, durationMs = timer.durationMs + 60_000)
                timers.save(updated)
                pending.setExact(TriggerKind.TIMER, id, endsAt)
                notifier.showTimerRunning(updated)
            }
            TimerState.PAUSED, TimerState.IDLE -> timers.save(
                timer.copy(remainingMs = timer.remainingMs + 60_000, durationMs = timer.durationMs + 60_000),
            )
            TimerState.RINGING -> Unit
        }
        surfaces.refreshAll()
    }

    suspend fun delete(id: Long) {
        pending.cancel(TriggerKind.TIMER, id)
        notifier.cancelTimer(id)
        timers.delete(id)
        surfaces.refreshAll()
    }

    private companion object {
        const val MAX_MS = 99L * 3_600_000 + 59 * 60_000 + 59_000
    }
}
