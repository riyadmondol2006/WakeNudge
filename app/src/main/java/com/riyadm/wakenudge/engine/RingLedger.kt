package com.riyadm.wakenudge.engine

import android.content.res.Resources
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.domain.repository.HistoryRepository
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.health.ExitPostMortem
import com.riyadm.wakenudge.health.ExitReasons
import com.riyadm.wakenudge.health.ProcessExit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Why an alarm did not go as it should. The first group says why it rang late or not at all; the second, what happened while it rang. */
@Serializable
enum class RingCause(val aboutTiming: Boolean) {
    FORCE_STOPPED(true),
    PHONE_OFF(true),
    KILLED_BY_SYSTEM(true),
    DELIVERED_LATE(true),
    UNEXPLAINED(true),
    RESTARTED_DURING_RING(false),
    STOPPED_DURING_RING(false),
    KILLED_DURING_RING(false),
    SOUND_FELL_BACK(false),
}

/** One line of the ledger. [count] is how many restarts for [RingCause.RESTARTED_DURING_RING]; [detail] is the engine's own note. */
@Serializable
data class LedgerEntry(
    val eventId: Long,
    val alarmId: Long,
    val label: String,
    val scheduledAt: Long,
    val firedAt: Long,
    val cause: RingCause,
    val rang: Boolean = true,
    val count: Int = 0,
    val detail: String = "",
) {
    val lateByMs: Long get() = firedAt - scheduledAt

    /** Once a cause of an event's timing is on record it stands: a restart days later does not make it "the phone was off". */
    val key: String get() = "$eventId:${if (cause.aboutTiming) "timing" else cause.name}"
}

/** Expected against actual, explained from what the phone can still tell. A pure function; what it cannot tell, it says it cannot. */
object RingLedgerPolicy {
    const val LATE_MS = 60_000L

    /** A system kill this close before the alarm can explain why it was late. An older one cannot: triggers outlive a kill. */
    private const val KILL_NEAR_MS = 10 * 60_000L

    /** @param bootAt when this phone last started, on the wall clock. */
    fun explain(event: AlarmEvent, exits: List<ProcessExit>, bootAt: Long, audioProblem: String, now: Long): List<LedgerEntry> = buildList {
        fun entry(cause: RingCause, rang: Boolean = true, count: Int = 0, detail: String = "") =
            add(LedgerEntry(event.id, event.alarmId, event.label, event.scheduledAt, event.firedAt, cause, rang, count, detail))

        // A ring that really happened fires a moment after its time; only an occurrence recorded after the fact has them equal.
        val neverRang = event.outcome == AlarmOutcome.MISSED && event.firedAt == event.scheduledAt
        if (neverRang || event.firedAt - event.scheduledAt > LATE_MS) {
            val lastExit = exits.filter { it.atMillis <= event.scheduledAt }.maxByOrNull { it.atMillis }
            val cause = when {
                // No process lived past this one up to the alarm, and a stop is what takes the triggers with it.
                lastExit != null && ExitReasons.isUserStop(lastExit) -> RingCause.FORCE_STOPPED
                // The phone started after the alarm time, and before it rang or was recorded as missed: it was off in between.
                bootAt > event.scheduledAt && bootAt <= (if (neverRang) event.endedAt ?: now else event.firedAt) -> RingCause.PHONE_OFF
                lastExit != null && ExitReasons.isSystemKill(lastExit) && event.scheduledAt - lastExit.atMillis <= KILL_NEAR_MS -> RingCause.KILLED_BY_SYSTEM
                neverRang -> RingCause.UNEXPLAINED
                else -> RingCause.DELIVERED_LATE
            }
            entry(cause, rang = !neverRang)
        }
        if (neverRang) return@buildList

        if (event.resumes > 0) entry(RingCause.RESTARTED_DURING_RING, count = event.resumes)
        val ringEnd = event.endedAt ?: now
        val during = exits.filter { it.atMillis in event.firedAt..ringEnd }
        if (during.any(ExitReasons::isUserStop)) entry(RingCause.STOPPED_DURING_RING)
        // Android clears away idle apps all the time, and a snooze leaves the app idle: only an unbroken ring says this mattered.
        if (event.snoozeCount == 0 && during.any(ExitReasons::isSystemKill)) entry(RingCause.KILLED_DURING_RING)
        val at = audioProblem.substringBefore('|').toLongOrNull()
        if (at != null && at in event.firedAt..ringEnd) entry(RingCause.SOUND_FELL_BACK, detail = audioProblem.substringAfter('|'))
    }
}

/**
 * Keeps what the ledger found and says so when an alarm did not ring on time. The history alone cannot be read again later
 * for this: whether the phone restarted is only known for the last start, so a cause is settled when it is first seen. It
 * lives in [RuntimeState], so it is local to this phone and in no backup. Reading and telling only: it cannot affect a ring.
 */
@Singleton
class RingLedger @Inject constructor(
    private val history: HistoryRepository,
    private val runtime: RuntimeState,
    private val exits: ExitPostMortem,
    private val notifier: Notifier,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
) {
    private val serializer = ListSerializer(LedgerEntry.serializer())

    /** Newest first. A file that cannot be read counts as empty. */
    fun entries(): List<LedgerEntry> = try {
        runtime.ringLedgerJson?.let { WakeJson.instance.decodeFromString(serializer, it) }.orEmpty()
    } catch (t: Throwable) {
        diagnostics.warn("Ledger", "the ledger could not be read: ${t.message}")
        emptyList()
    }

    /** Looks at recent history for anything new. At each process start, and when Alarm Health opens. */
    suspend fun update() {
        try {
            val now = time.nowMillis()
            val bootAt = now - time.elapsedMillis()
            val processExits = exits.exits()
            val audio = runtime.lastAudioProblem
            val found = history.recent(RECENT_EVENTS).filter { now - it.scheduledAt in 0..KEEP_MS }
                .flatMap { RingLedgerPolicy.explain(it, processExits, bootAt, audio, now) }
            val known = entries()
            val seen = known.mapTo(hashSetOf()) { it.key }
            val fresh = found.filter { it.key !in seen }
            val kept = (fresh + known).filter { now - it.scheduledAt <= KEEP_MS }.sortedByDescending { it.scheduledAt }.take(MAX_ENTRIES)
            if (kept != known) runtime.ringLedgerJson = WakeJson.instance.encodeToString(serializer, kept)
            fresh.forEach { diagnostics.warn("Ledger", "alarm ${it.alarmId} at ${it.scheduledAt}: ${it.cause}") }
            // A notice is for what just happened, and for what kept an alarm from ringing on time. The rest waits in Alarm Health.
            fresh.filter { it.cause.aboutTiming && now - it.scheduledAt <= TELL_WITHIN_MS }
                .groupBy { it.alarmId }
                .values.map { list -> list.maxBy { it.scheduledAt } }
                .forEach(notifier::showRingExplained)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            diagnostics.warn("Ledger", "could not look at recent alarms: ${t.message}")
        }
    }

    private companion object {
        const val RECENT_EVENTS = 30
        const val MAX_ENTRIES = 30
        const val KEEP_MS = 7 * 24 * 3_600_000L
        const val TELL_WITHIN_MS = 24 * 3_600_000L
    }
}

/** The line a person reads, on Alarm Health and in the notification. */
fun LedgerEntry.describe(res: Resources): String = when (cause) {
    RingCause.FORCE_STOPPED -> res.getString(if (rang) R.string.ledger_force_stopped_late else R.string.ledger_force_stopped)
    RingCause.PHONE_OFF -> res.getString(if (rang) R.string.ledger_phone_off_late else R.string.ledger_phone_off)
    RingCause.KILLED_BY_SYSTEM -> res.getString(R.string.ledger_killed)
    RingCause.DELIVERED_LATE -> max(1L, lateByMs / 60_000L).toInt().let { res.getQuantityString(R.plurals.ledger_late, it, it) }
    RingCause.UNEXPLAINED -> res.getString(R.string.ledger_unexplained)
    RingCause.RESTARTED_DURING_RING -> res.getQuantityString(R.plurals.ledger_restarted, count, count)
    RingCause.STOPPED_DURING_RING -> res.getString(R.string.ledger_stopped)
    RingCause.KILLED_DURING_RING -> res.getString(R.string.ledger_killed_during)
    RingCause.SOUND_FELL_BACK -> res.getString(R.string.ledger_sound, detail)
}
