package com.riyadm.wakenudge.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoalescingTriggerTest {

    @Test fun `a burst of triggers becomes one action after the delay`() = runTest {
        var runs = 0
        val trigger = CoalescingTrigger(this, 300) { runs++ }

        repeat(50) { trigger.trigger() }
        advanceTimeBy(299)
        assertEquals("nothing yet", 0, runs)
        advanceTimeBy(2)
        assertEquals(1, runs)

        advanceUntilIdle()
        assertEquals("the burst is fully absorbed", 1, runs)
    }

    @Test fun `triggers spread within the window still make a single run`() = runTest {
        var runs = 0
        val trigger = CoalescingTrigger(this, 300) { runs++ }

        trigger.trigger()
        advanceTimeBy(200)
        trigger.trigger()
        advanceTimeBy(99)
        trigger.trigger()
        advanceTimeBy(2)
        assertEquals(1, runs)
        advanceUntilIdle()
        assertEquals(1, runs)
    }

    @Test fun `a trigger after a run starts a new round`() = runTest {
        var runs = 0
        val trigger = CoalescingTrigger(this, 300) { runs++ }

        trigger.trigger()
        advanceTimeBy(301)
        assertEquals(1, runs)
        trigger.trigger()
        advanceTimeBy(301)
        assertEquals(2, runs)
    }

    @Test fun `a trigger while the action is running is not lost`() = runTest {
        var runs = 0
        lateinit var trigger: CoalescingTrigger
        trigger = CoalescingTrigger(this, 300) {
            runs++
            if (runs == 1) trigger.trigger()
        }

        trigger.trigger()
        advanceUntilIdle()
        assertEquals(2, runs)
    }

    @Test fun `a failing action does not stop later rounds`() = runTest {
        var runs = 0
        val failures = mutableListOf<Throwable>()
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, t -> failures += t }
        val trigger = CoalescingTrigger(kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob() + handler), 300) {
            runs++
            if (runs == 1) error("boom")
        }

        trigger.trigger()
        advanceTimeBy(301)
        trigger.trigger()
        advanceTimeBy(301)
        assertEquals(2, runs)
        assertEquals(1, failures.size)
    }
}
