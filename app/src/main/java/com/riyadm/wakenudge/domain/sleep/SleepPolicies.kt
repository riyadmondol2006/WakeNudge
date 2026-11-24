package com.riyadm.wakenudge.domain.sleep

import com.riyadm.wakenudge.domain.model.AlarmOutcome
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** A clip already kept for tonight, as the keep-or-drop decision sees it. */
data class ClipRef(val id: Long, val atMs: Long, val peakDb: Float)

sealed interface ClipDecision {
    data object Add : ClipDecision
    data class Replace(val id: Long) : ClipDecision
    data object Drop : ClipDecision
}

/**
 * Which snore clips are kept: at most [SleepLimits.MAX_CLIPS_PER_NIGHT] per night, the loudest, spread out so that three
 * clips are not the same minute of snoring. A candidate within [MIN_GAP_MS] of a kept clip replaces it only when it is
 * louder; otherwise a free place is taken, or the quietest kept clip is replaced by a louder one.
 */
object ClipPolicy {
    const val MIN_GAP_MS = 5 * 60_000L

    fun decide(kept: List<ClipRef>, atMs: Long, peakDb: Float): ClipDecision {
        val near = kept.filter { kotlin.math.abs(it.atMs - atMs) < MIN_GAP_MS }
        if (near.isNotEmpty()) {
            val loudestNear = near.maxBy { it.peakDb }
            return if (peakDb > loudestNear.peakDb && near.size == 1) ClipDecision.Replace(loudestNear.id) else ClipDecision.Drop
        }
        if (kept.size < SleepLimits.MAX_CLIPS_PER_NIGHT) return ClipDecision.Add
        val quietest = kept.minBy { it.peakDb }
        return if (peakDb > quietest.peakDb) ClipDecision.Replace(quietest.id) else ClipDecision.Drop
    }

    fun expired(createdAtMs: Long, nowMs: Long): Boolean = nowMs - createdAtMs >= SleepLimits.CLIP_RETENTION_MS || createdAtMs > nowMs + 24 * 60 * 60_000L

    /** How the loudness of a clip is described: by how far it stood above the room's quiet level. */
    fun loudness(aboveFloorDb: Float): Loudness = when {
        aboveFloorDb >= 25f -> Loudness.VERY_LOUD
        aboveFloorDb >= 15f -> Loudness.LOUD
        else -> Loudness.SOFT
    }

    enum class Loudness { SOFT, LOUD, VERY_LOUD }
}

/** How tracking reacts to an alarm, read from the ring session without ever acting on it. */
object RingWatchPolicy {
    /** What the watcher knows about a ring that started while tracking. */
    data class SeenRing(val eventId: Long, val isTimer: Boolean, val isTest: Boolean)

    /** Any ring stops the microphone at once; it stays off for the rest of the session (Android would not let it restart). */
    fun stopsMicrophone(ringing: Boolean): Boolean = ringing

    /**
     * Whether a ring that has gone away ends the session. A test alarm has no snooze and no history: gone means dealt with.
     * A timer never ends a night. An alarm ends it when it was dismissed (or stopped with Emergency); a snooze or a ring that
     * timed out unanswered keeps tracking, since the sleeper may still be in bed. If the history cannot be read, the ring
     * being gone is taken as dismissed.
     */
    fun endsSession(ring: SeenRing, outcome: AlarmOutcome?, snoozeUntil: Long, historyReadable: Boolean): Boolean = when {
        ring.isTimer -> false
        ring.isTest -> true
        !historyReadable -> true
        outcome == AlarmOutcome.DISMISSED || outcome == AlarmOutcome.EMERGENCY_STOPPED -> true
        outcome == AlarmOutcome.RINGING && snoozeUntil > 0 -> false
        outcome == AlarmOutcome.MISSED -> false
        // RINGING without a snooze: the history has not caught up yet, or the ring was replaced. Keep going.
        else -> false
    }
}

/** Session bookkeeping that does not need Android. */
object SessionRules {
    fun reachedLimit(startElapsedMs: Long, nowElapsedMs: Long): Boolean = nowElapsedMs - startElapsedMs >= SleepLimits.MAX_SESSION_MS

    /**
     * Where a session that was never closed (the process died) ends: after its last stored epoch, never before it began and
     * never later than the session limit.
     */
    fun closeStale(startedAtMs: Long, lastEpochIndex: Int?): Long {
        val epochs = (lastEpochIndex ?: -1) + 1
        return startedAtMs + (epochs * SleepLimits.EPOCH_MS).coerceIn(0, SleepLimits.MAX_SESSION_MS)
    }

    fun isShort(startedAtMs: Long, endedAtMs: Long): Boolean = endedAtMs - startedAtMs < SleepLimits.SHORT_SESSION_MS
}

/** A finished session with its estimate, as the report sees it. */
data class Night(
    val sessionId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val summary: NightSummary,
    val usedMicrophone: Boolean,
) {
    val short: Boolean get() = summary.short
}

/** The week picker and the week's numbers. Nights belong to the day they ended on: the morning you woke up. */
object SleepWeeks {
    fun weekStart(date: LocalDate, firstDay: DayOfWeek): LocalDate = date.with(TemporalAdjusters.previousOrSame(firstDay))

    fun days(weekStart: LocalDate): List<LocalDate> = (0L until 7L).map { weekStart.plusDays(it) }

    fun nightDate(night: Night, zone: ZoneId): LocalDate = Instant.ofEpochMilli(night.endedAt).atZone(zone).toLocalDate()

    /** Going forward stops at the week that holds today. */
    fun canGoForward(shownWeekStart: LocalDate, today: LocalDate, firstDay: DayOfWeek): Boolean = shownWeekStart < weekStart(today, firstDay)

    /**
     * One night per day of the week: the longest that is not short and has an estimate, else the longest one with an
     * estimate, else the longest. Naps and false starts stay in the list but do not take the day's bar.
     */
    fun mainNights(nights: List<Night>, weekStart: LocalDate, zone: ZoneId): Map<LocalDate, Night> {
        val week = days(weekStart).toSet()
        return nights.groupBy { nightDate(it, zone) }.filterKeys { it in week }.mapValues { (_, list) ->
            list.filter { !it.short && it.summary.hasEstimate }.maxByOrNull { it.endedAt - it.startedAt }
                ?: list.filter { it.summary.hasEstimate }.maxByOrNull { it.endedAt - it.startedAt }
                ?: list.maxBy { it.endedAt - it.startedAt }
        }
    }

    data class Averages(val nights: Int, val asleepMs: Long?, val latencyMs: Long?, val awakeAfterOnsetMs: Long?)

    /** Averages over the main nights that have an estimate and are not short. */
    fun averages(main: Collection<Night>): Averages {
        val counted = main.filter { it.summary.hasEstimate && !it.short }
        if (counted.isEmpty()) return Averages(0, null, null, null)
        val latencies = counted.mapNotNull { it.summary.latencyMs }
        return Averages(
            nights = counted.size,
            asleepMs = counted.sumOf { it.summary.asleepMs } / counted.size,
            latencyMs = if (latencies.isEmpty()) null else latencies.sum() / latencies.size,
            awakeAfterOnsetMs = counted.sumOf { it.summary.awakeAfterOnsetMs } / counted.size,
        )
    }
}
