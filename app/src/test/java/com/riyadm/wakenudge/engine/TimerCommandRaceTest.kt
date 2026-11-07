package com.riyadm.wakenudge.engine

import com.riyadm.wakenudge.data.backup.CountingSurfaces
import com.riyadm.wakenudge.domain.model.CountdownTimer
import com.riyadm.wakenudge.domain.model.TimerState
import com.riyadm.wakenudge.domain.repository.TimerRepository
import com.riyadm.wakenudge.engine.notify.Notifier
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Timer commands arriving together (a notification button and the screen, or a widget) must leave the trigger armed exactly
 * while the timer runs. Each command reads the timer, then saves it and arms or cancels the trigger; the repository here
 * yields between those steps the way a database does, so commands interleave.
 */
class TimerCommandRaceTest {
    private class SlowTimers : TimerRepository {
        val rows = ConcurrentHashMap<Long, CountdownTimer>()
        override fun observeTimers() = throw UnsupportedOperationException()
        override suspend fun getTimers() = rows.values.toList()
        override suspend fun getTimer(id: Long): CountdownTimer? { yield(); return rows[id].also { yield() } }
        override suspend fun save(timer: CountdownTimer): Long {
            yield()
            val id = if (timer.id == 0L) (rows.keys.maxOrNull() ?: 0L) + 1 else timer.id
            rows[id] = timer.copy(id = id)
            yield()
            return id
        }
        override suspend fun delete(id: Long) { rows.remove(id) }
    }

    @Test fun `parallel start, pause and add-a-minute never leave an orphan or a missing trigger`() = runBlocking {
        repeat(50) { round ->
            val armed = ConcurrentHashMap.newKeySet<Long>()
            val pending = mockk<PendingAlarms>(relaxed = true)
            every { pending.setExact(TriggerKind.TIMER, any(), any(), any(), any(), any()) } answers { armed += secondArg<Long>(); true }
            every { pending.cancel(TriggerKind.TIMER, any(), any()) } answers { armed -= secondArg<Long>() }
            val timers = SlowTimers()
            val controller = TimerController(timers, pending, mockk<Notifier>(relaxed = true), TestClock(), CountingSurfaces())
            val id = controller.create(120_000, "race", startNow = false)
            coroutineScope {
                (1..30).map { n ->
                    async(Dispatchers.Default) {
                        when (n % 3) {
                            0 -> controller.pause(id)
                            1 -> controller.start(id)
                            else -> controller.addMinute(id)
                        }
                    }
                }.awaitAll()
            }
            val t = timers.rows.getValue(id)
            assertEquals("round $round: armed exactly while running ($t)", t.state == TimerState.RUNNING, id in armed)
        }
    }
}
