package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.WakeCheck
import com.riyadm.wakenudge.domain.model.WakeCheckSettings
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.repository.WakeCheckRepository
import com.riyadm.wakenudge.engine.notify.Notifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A "Still awake?" check that asks for proof cannot be answered by a sleeper: no tap, no Cancel, only Focus Tap or steps. */
class WakeProofTest {
    private val clock = TestClock()
    private val alarms = MemoryAlarms()
    private val history = MemoryHistory()
    private val runtime = realRuntime()
    private val notifier = mockk<Notifier>(relaxed = true)
    private val pending = mockk<PendingAlarms>(relaxed = true).also {
        every { it.setExact(any(), any(), any(), any(), any(), any()) } returns true
        every { it.setAlarmClock(any(), any(), any(), any(), any(), any()) } returns true
    }
    private val checks = object : WakeCheckRepository {
        val rows = HashMap<Long, WakeCheck>()
        override suspend fun getAll(): List<WakeCheck> = rows.values.toList()
        override fun observeAll(): Flow<List<WakeCheck>> = flowOf(rows.values.toList())
        override suspend fun get(alarmId: Long): WakeCheck? = rows[alarmId]
        override suspend fun save(check: WakeCheck) { rows[check.alarmId] = check }
        override suspend fun delete(alarmId: Long) { rows.remove(alarmId) }
        override suspend fun clear() = rows.clear()
    }

    /** The hardware step counter: null when the phone has none or it is not allowed. */
    private var stepTotal: Float? = 1_000f
    private val controller = WakeCheckController(
        checks, alarms, history, pending, notifier, clock, SilentLog(), runtime,
        StepTotal { stepTotal }, CoroutineScope(Dispatchers.Unconfined),
    )

    private fun alarm(proof: Boolean, checksCount: Int = 1) = alarms.add(
        Alarm(label = "a", wakeCheck = WakeCheckSettings(enabled = true, delayMinutes = 5, maxChecks = checksCount, requireProof = proof)),
    )

    private suspend fun dismissed(a: Alarm): Long {
        val event = history.start(AlarmEvent(alarmId = a.id, label = "a", scheduledAt = clock.now, firedAt = clock.now))
        controller.startAfterDismissal(a, event, 0)
        return event
    }

    @Test fun `a plain tap, or a button left on a notification, does not answer a check that needs proof`() = runBlocking {
        val a = alarm(proof = true)
        dismissed(a)
        assertFalse(controller.answer(a.id))
        assertNotNull("the check runs its course", checks.rows[a.id])
        assertTrue(controller.answer(a.id, WakeProof.FOCUS_TAP))
        assertNull(checks.rows[a.id])
    }

    @Test fun `a check without proof is answered by a tap, as before`() = runBlocking {
        val a = alarm(proof = false)
        dismissed(a)
        assertTrue(controller.answer(a.id))
        assertNull(checks.rows[a.id])
    }

    @Test fun `Cancel is refused on a check that needs proof, and works on one that does not`() = runBlocking {
        val strict = alarm(proof = true)
        val plain = alarm(proof = false)
        dismissed(strict)
        dismissed(plain)
        assertFalse(controller.cancelByUser(strict.id))
        assertNotNull(checks.rows[strict.id])
        assertTrue(controller.cancelByUser(plain.id))
        assertNull(checks.rows[plain.id])
    }

    @Test fun `thirty steps since the dismissal answer it without a prompt, twenty-nine do not`() = runBlocking {
        val walked = alarm(proof = true)
        val event = dismissed(walked)
        stepTotal = 1_030f
        controller.onPrompt(walked.id)
        assertNull(checks.rows[walked.id])
        assertEquals(WakeCheckStatus.PASSED, history.get(event)!!.wakeCheck)
        verify(exactly = 0) { notifier.showWakeCheck(any(), any(), any()) }

        stepTotal = 1_000f
        val stayed = alarm(proof = true)
        dismissed(stayed)
        stepTotal = 1_029f
        controller.onPrompt(stayed.id)
        assertEquals(WakeCheck.Phase.PROMPTED, checks.rows[stayed.id]!!.phase)
        verify { notifier.showWakeCheck(any(), any(), any()) }
    }

    @Test fun `no step counter, or a restart since the dismissal, is no proof`() = runBlocking {
        stepTotal = null
        val none = alarm(proof = true)
        dismissed(none)
        stepTotal = 5_000f
        controller.onPrompt(none.id)
        assertEquals(WakeCheck.Phase.PROMPTED, checks.rows[none.id]!!.phase)

        stepTotal = 1_000f
        val restarted = alarm(proof = true)
        dismissed(restarted)
        stepTotal = 12f
        controller.onPrompt(restarted.id)
        assertEquals(WakeCheck.Phase.PROMPTED, checks.rows[restarted.id]!!.phase)
    }

    @Test fun `each of two checks counts its own steps`() = runBlocking {
        val a = alarm(proof = true, checksCount = 2)
        dismissed(a)
        stepTotal = 1_040f
        controller.onPrompt(a.id)
        assertEquals("first check passed, second waits", 1, checks.rows[a.id]!!.checksDone)
        stepTotal = 1_060f
        controller.onPrompt(a.id)
        assertEquals("only 20 steps since the first check", WakeCheck.Phase.PROMPTED, checks.rows[a.id]!!.phase)
    }

    @Test fun `the prompt of a check that needs proof comes up to three minutes late, the plain one on time`() = runBlocking {
        val five = 5 * 60_000L
        controller.random = object : Random() { override fun nextBits(bitCount: Int) = 0 }
        val early = alarm(proof = true)
        dismissed(early)
        assertEquals(clock.now + five, checks.rows[early.id]!!.promptAt)

        controller.random = Random(7)
        val spread = (1..50).map {
            val a = alarm(proof = true)
            dismissed(a)
            checks.rows[a.id]!!.promptAt - clock.now
        }
        assertTrue(spread.all { it in five..five + WakeProofPolicy.MAX_EXTRA_DELAY_MS })
        assertTrue("it varies", spread.toSet().size > 10)

        val plain = alarm(proof = false)
        dismissed(plain)
        assertEquals(clock.now + five, checks.rows[plain.id]!!.promptAt)
    }

    @Test fun `the policy`() {
        assertTrue(WakeProofPolicy.stepsAnswer(100f, 130f))
        assertFalse(WakeProofPolicy.stepsAnswer(100f, 129f))
        assertFalse(WakeProofPolicy.stepsAnswer(null, 130f))
        assertFalse(WakeProofPolicy.stepsAnswer(100f, null))
        assertFalse(WakeProofPolicy.stepsAnswer(100f, 40f))
        assertTrue(WakeProofPolicy.accepts(requireProof = false, WakeProof.NONE))
        assertFalse(WakeProofPolicy.accepts(requireProof = true, WakeProof.NONE))
        assertTrue(WakeProofPolicy.accepts(requireProof = true, WakeProof.FOCUS_TAP))
        // "I can't do timed or physical missions" answers with sums instead of Focus Tap.
        assertTrue(WakeProofPolicy.accepts(requireProof = true, WakeProof.SUMS))
        assertTrue(WakeProofPolicy.accepts(requireProof = true, WakeProof.STEPS))
    }
}
