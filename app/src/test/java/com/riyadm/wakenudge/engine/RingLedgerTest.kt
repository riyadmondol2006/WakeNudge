package com.riyadm.wakenudge.engine

import android.app.ApplicationExitInfo
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.health.ExitPostMortem
import com.riyadm.wakenudge.health.ProcessExit
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val MIN = 60_000L

class RingLedgerPolicyTest {
    private val at = 1_800_000_000_000L
    private val now = at + 3 * 3_600_000L
    private val bootLongAgo = at - 48 * 3_600_000L

    private fun event(fired: Long = at + 300, outcome: AlarmOutcome = AlarmOutcome.DISMISSED, ended: Long? = fired + 2 * MIN, snoozes: Int = 0, resumes: Int = 0) =
        AlarmEvent(id = 5, alarmId = 2, label = "work", scheduledAt = at, firedAt = fired, endedAt = ended, outcome = outcome, snoozeCount = snoozes, resumes = resumes)

    /** What a phone off through the alarm, or a force stop, leaves: written after the fact, at the alarm's own time. */
    private val neverRang = event(fired = at, outcome = AlarmOutcome.MISSED, ended = now)

    private fun causes(e: AlarmEvent, exits: List<ProcessExit> = emptyList(), bootAt: Long = bootLongAgo, audio: String = "") =
        RingLedgerPolicy.explain(e, exits, bootAt, audio, now).map { it.cause }

    private fun exit(t: Long, reason: Int, description: String? = null) = ProcessExit(t, reason, description)

    @Test fun `an alarm that rang on time and was dealt with has nothing to say`() {
        assertEquals(emptyList<RingCause>(), causes(event()))
        assertEquals("ignored until it timed out is not a failure of the alarm", emptyList<RingCause>(), causes(event(outcome = AlarmOutcome.MISSED)))
    }

    @Test fun `a force stop before the alarm is named, whether it then rang late or not at all`() {
        val stopped = listOf(exit(at - 3 * 3_600_000L, ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals(listOf(RingCause.FORCE_STOPPED), causes(neverRang, stopped))
        assertEquals(listOf(RingCause.FORCE_STOPPED), causes(event(fired = at + 40 * MIN), stopped))
        assertTrue(!RingLedgerPolicy.explain(neverRang, stopped, bootLongAgo, "", now).single().rang)
    }

    @Test fun `a phone that started after the alarm time was off or restarting`() {
        assertEquals(listOf(RingCause.PHONE_OFF), causes(neverRang, bootAt = at + 30 * MIN))
        assertEquals(listOf(RingCause.PHONE_OFF), causes(event(fired = at + 31 * MIN), bootAt = at + 30 * MIN))
    }

    @Test fun `a restart after a late alarm does not make it the phone's fault`() {
        assertEquals(listOf(RingCause.DELIVERED_LATE), causes(event(fired = at + 5 * MIN), bootAt = at + 60 * MIN))
    }

    @Test fun `a system kill just before a late alarm is named, an old one is not`() {
        assertEquals(listOf(RingCause.KILLED_BY_SYSTEM), causes(event(fired = at + 5 * MIN), listOf(exit(at - 2 * MIN, ApplicationExitInfo.REASON_LOW_MEMORY))))
        assertEquals(listOf(RingCause.DELIVERED_LATE), causes(event(fired = at + 5 * MIN), listOf(exit(at - 5 * 3_600_000L, ApplicationExitInfo.REASON_LOW_MEMORY))))
    }

    @Test fun `more than a minute late with no known cause says only that`() {
        assertEquals(listOf(RingCause.DELIVERED_LATE), causes(event(fired = at + 61_000)))
        assertEquals(emptyList<RingCause>(), causes(event(fired = at + 59_000)))
    }

    @Test fun `an alarm that never rang with nothing to explain it says so honestly`() {
        assertEquals(listOf(RingCause.UNEXPLAINED), causes(neverRang))
    }

    @Test fun `restarts, a stop and a kill during the ring are each told`() {
        val e = event(resumes = 3, ended = at + 20 * MIN)
        val exits = listOf(exit(at + 5 * MIN, ApplicationExitInfo.REASON_USER_REQUESTED), exit(at + 9 * MIN, 0, "killed by MemoryLimiter"))
        assertEquals(listOf(RingCause.RESTARTED_DURING_RING, RingCause.STOPPED_DURING_RING, RingCause.KILLED_DURING_RING), causes(e, exits))
        assertEquals(3, RingLedgerPolicy.explain(e, exits, bootLongAgo, "", now).first().count)
    }

    @Test fun `Android clearing away the idle app during a snooze is not blamed`() {
        val e = event(snoozes = 1, ended = at + 30 * MIN)
        assertEquals(emptyList<RingCause>(), causes(e, listOf(exit(at + 12 * MIN, ApplicationExitInfo.REASON_LOW_MEMORY))))
    }

    @Test fun `a backup sound during the ring is told with what the engine noted, one at another time is not`() {
        val e = event(ended = at + 10 * MIN)
        val entries = RingLedgerPolicy.explain(e, emptyList(), bootLongAgo, "${at + MIN}|the chosen sound could not be opened", now)
        assertEquals(listOf(RingCause.SOUND_FELL_BACK), entries.map { it.cause })
        assertEquals("the chosen sound could not be opened", entries.single().detail)
        assertEquals(emptyList<RingCause>(), causes(e, audio = "${at - 3_600_000L}|old"))
    }
}

class RingLedgerTest {
    private val clock = TestClock()
    private val history = MemoryHistory()
    private val runtime = realRuntime()
    private val notifier = mockk<Notifier>(relaxed = true)
    private var exits = emptyList<ProcessExit>()
    private val postMortem = mockk<ExitPostMortem>().also { every { it.exits() } answers { exits } }
    private val ledger = RingLedger(history, runtime, postMortem, notifier, clock, SilentLog())

    private suspend fun missedWhileForceStopped(): AlarmEvent {
        val at = clock.now - 2 * 3_600_000L
        exits = listOf(ProcessExit(at - 3_600_000L, ApplicationExitInfo.REASON_USER_REQUESTED, "stop"))
        val id = history.start(AlarmEvent(alarmId = 3, label = "work", scheduledAt = at, firedAt = at, endedAt = clock.now, outcome = AlarmOutcome.MISSED))
        return history.get(id)!!
    }

    @Test fun `a force stop through an alarm time is explained at the next start, once`() = runBlocking {
        missedWhileForceStopped()
        ledger.update()
        verify(exactly = 1) { notifier.showRingExplained(match { it.cause == RingCause.FORCE_STOPPED && !it.rang && it.alarmId == 3L }) }
        ledger.update()
        verify(exactly = 1) { notifier.showRingExplained(any()) }
        assertEquals(listOf(RingCause.FORCE_STOPPED), ledger.entries().map { it.cause })
    }

    @Test fun `what was found first stands, even after the phone restarts`() = runBlocking {
        missedWhileForceStopped()
        ledger.update()
        clock.restart()
        ledger.update()
        assertEquals(listOf(RingCause.FORCE_STOPPED), ledger.entries().map { it.cause })
    }

    @Test fun `something that went wrong long ago goes in the list without a notification`() = runBlocking {
        val at = clock.now - 3 * 24 * 3_600_000L
        history.start(AlarmEvent(alarmId = 3, label = "x", scheduledAt = at, firedAt = at + 10 * MIN, endedAt = at + 12 * MIN, outcome = AlarmOutcome.DISMISSED))
        ledger.update()
        assertEquals(listOf(RingCause.DELIVERED_LATE), ledger.entries().map { it.cause })
        verify(exactly = 0) { notifier.showRingExplained(any()) }
    }

    @Test fun `an unreadable history or a broken saved ledger never throws`() = runBlocking {
        runtime.ringLedgerJson = "{not json"
        assertEquals(emptyList<LedgerEntry>(), ledger.entries())
        history.failure = IOException("database unreadable")
        ledger.update()
        every { postMortem.exits() } throws SecurityException("no")
        ledger.update()
    }
}
