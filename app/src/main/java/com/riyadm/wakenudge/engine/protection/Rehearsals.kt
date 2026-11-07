package com.riyadm.wakenudge.engine.protection

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.core.WakeJson
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.SnoozeSettings
import com.riyadm.wakenudge.domain.repository.DiagnosticsLog
import com.riyadm.wakenudge.engine.PendingAlarms
import com.riyadm.wakenudge.engine.RingIds
import com.riyadm.wakenudge.engine.RuntimeState
import com.riyadm.wakenudge.engine.TriggerKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which alarms have had a finished rehearsal on this phone: a real ring of a copy of the alarm, ended with its missions.
 * Kept in [RuntimeState], never in the alarm or a backup, because it was agreed on one phone with one set of missions.
 * Changing an alarm's missions makes it unrehearsed again. Reading never throws: unreadable means "not rehearsed".
 */
class RehearsalRecord(private val runtime: RuntimeState) {

    /** Whether [alarm], with the missions it has now, has had a finished rehearsal. */
    fun isRehearsed(alarm: Alarm): Boolean = entries()[alarm.id] == fingerprint(alarm)

    /** Whether any rehearsal has finished: what device admin waits for. */
    val anyFinished: Boolean get() = entries().isNotEmpty()

    fun begin(alarmId: Long) = guarded { runtime.rehearsalOf = alarmId }

    /** A test ring ended; [testAlarm] is the copy that rang. Only a rehearsal ended with its missions counts. */
    fun ended(testAlarm: Alarm, finished: Boolean) = guarded {
        val of = runtime.rehearsalOf
        if (of < 0) return@guarded
        runtime.rehearsalOf = -1
        if (finished) {
            val next = entries() + (of to fingerprint(testAlarm))
            runtime.rehearsedRaw = next.entries.joinToString(",") { "${it.key}:${it.value}" }
        }
    }

    private fun entries(): Map<Long, String> = try {
        runtime.rehearsedRaw.split(',').mapNotNull { part ->
            val (id, print) = part.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toLongOrNull()?.let { it to print }
        }.toMap()
    } catch (_: Throwable) {
        emptyMap()
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // A rehearsal that cannot be recorded is simply not counted; it never affects the ring.
        }
    }

    companion object {
        /** The missions as they would be asked for, so a change of mission, difficulty, count or code needs a new rehearsal. */
        fun fingerprint(alarm: Alarm): String =
            alarm.missions.joinToString("|") { m -> "${m.type}/${m.difficulty}/${m.count}/${m.params.toSortedMap()}" }
                .hashCode().toUInt().toString(16)
    }
}

/**
 * "Try it now": a real ring of a copy of the alarm, a few seconds from now, through the real trigger, ring service, ring
 * screen and [com.riyadm.wakenudge.engine.policy.RingPolicy], exactly as its morning would be except that it is a test:
 * no snooze, no backup, nothing saved to come back after a restart, its way out always there, and it gives up after 10
 * minutes. It counts as rehearsed only when ended with its missions.
 */
@Singleton
class Rehearsals @Inject constructor(
    private val runtime: RuntimeState,
    private val pending: PendingAlarms,
    private val time: TimeSource,
    private val diagnostics: DiagnosticsLog,
) {
    private val record get() = RehearsalRecord(runtime)

    fun isRehearsed(alarm: Alarm): Boolean = record.isRehearsed(alarm)

    val anyFinished: Boolean get() = record.anyFinished

    /** Arms the rehearsal of [alarm]; returns when it rings, or null if Android refused. */
    fun start(alarm: Alarm, delaySeconds: Int = DELAY_SECONDS): Long? {
        val at = time.nowMillis() + delaySeconds * 1000L
        runtime.testAlarmJson = WakeJson.instance.encodeToString(Alarm.serializer(), copyFor(alarm))
        record.begin(alarm.id)
        if (!pending.setAlarmClock(TriggerKind.ALARM, RingIds.TEST_ALARM_ID, at)) return null
        diagnostics.info("Protection", "rehearsal of alarm ${alarm.id} armed for +${delaySeconds}s")
        return at
    }

    companion object {
        const val DELAY_SECONDS = 5
        const val TIMEOUT_MINUTES = 10

        /** The ring a rehearsal plays: the alarm as it is, minus what a trial must not have. */
        fun copyFor(alarm: Alarm): Alarm = alarm.copy(
            id = RingIds.TEST_ALARM_ID,
            snooze = SnoozeSettings(enabled = false),
            snoozeMissions = emptyList(),
            antiOversleep = alarm.antiOversleep.copy(
                backupEnabled = false,
                ringTimeoutMinutes = TIMEOUT_MINUTES,
                wayOutRemoved = false,
                rotateMissions = false,
            ),
        )
    }
}
