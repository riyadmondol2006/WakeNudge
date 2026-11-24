package com.riyadm.wakenudge.engine.habits

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.domain.habits.Habit
import com.riyadm.wakenudge.domain.habits.HabitCheckIn
import com.riyadm.wakenudge.domain.habits.HabitData
import com.riyadm.wakenudge.domain.habits.HabitRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HabitReminderHandlerTest {
    private val today = LocalDate.of(2026, 11, 24)
    private val habit = Habit(id = 7, name = "Read", createdEpochDay = today.minusDays(10).toEpochDay(), reminderMinutes = 480)
    private val repository = mockk<HabitRepository>()
    private val scheduler = mockk<HabitReminderScheduler>(relaxed = true)
    private val notifier = mockk<HabitNotifier>(relaxed = true)
    private val time = object : TimeSource {
        override fun nowMillis() = Instant.parse("2026-11-24T06:00:00Z").toEpochMilli()
        override fun zone() = ZoneId.of("UTC")
    }
    private val handler = HabitReminderHandler(repository, scheduler, notifier, time)

    @Test fun `a current due reminder posts and the next one is armed`() = runTest {
        coEvery { repository.habit(7) } returns habit
        coEvery { repository.data() } returns HabitData(listOf(habit))
        coEvery { scheduler.schedule(habit) } returns true
        handler.remind(7, today.toEpochDay())
        coVerify(exactly = 1) { notifier.show(habit, today.toEpochDay()) }
        coVerify(exactly = 1) { scheduler.schedule(habit) }
    }

    @Test fun `an already done habit does not post again`() = runTest {
        coEvery { repository.habit(7) } returns habit
        coEvery { repository.data() } returns HabitData(listOf(habit), setOf(HabitCheckIn(7, today.toEpochDay())))
        coEvery { scheduler.schedule(habit) } returns true
        handler.remind(7, today.toEpochDay())
        coVerify(exactly = 0) { notifier.show(any(), any()) }
        coVerify(exactly = 1) { scheduler.schedule(habit) }
    }

    @Test fun `a stale trigger never posts but still repairs the schedule`() = runTest {
        coEvery { repository.habit(7) } returns habit
        coEvery { scheduler.schedule(habit) } returns true
        handler.remind(7, today.minusDays(1).toEpochDay())
        coVerify(exactly = 0) { repository.data() }
        coVerify(exactly = 0) { notifier.show(any(), any()) }
        coVerify(exactly = 1) { scheduler.schedule(habit) }
    }

    @Test fun `a deleted habit has its trigger and notification retired`() = runTest {
        coEvery { repository.habit(7) } returns null
        handler.remind(7, today.toEpochDay())
        coVerify(exactly = 1) { scheduler.cancel(7) }
        coVerify(exactly = 0) { notifier.show(any(), any()) }
    }

    @Test fun `done uses the notification date and dismisses its notification`() = runTest {
        coEvery { repository.setDone(7, today, true, today) } returns true
        handler.done(7, today.toEpochDay())
        coVerify(exactly = 1) { repository.setDone(7, today, true, today) }
        coVerify(exactly = 1) { notifier.cancel(7) }
    }

    @Test fun `habit ids stay outside the existing notification and request ranges`() {
        assertTrue(HabitReminderIds.notification(1) > 125_535)
        assertEquals(700_001, HabitReminderIds.reminderRequest(1))
        assertEquals(710_001, HabitReminderIds.doneRequest(1))
        assertEquals(720_001, HabitReminderIds.openRequest(1))
    }
}
