package com.riyadm.wakenudge.domain

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator.Suppression
import com.riyadm.wakenudge.domain.scheduling.SchedulingContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextTriggerCalculatorTest {
    private val calc = NextTriggerCalculator()
    private val utc = ZoneId.of("UTC")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, zone: ZoneId = utc, sec: Int = 0) =
        ZonedDateTime.of(LocalDateTime.of(y, m, d, h, min, sec), zone)

    private fun alarm(
        hour: Int = 7,
        minute: Int = 0,
        days: Collection<DayOfWeek> = emptyList(),
        enabled: Boolean = true,
        block: Alarm.() -> Alarm = { this },
    ) = Alarm(id = 1, hour = hour, minute = minute, enabled = enabled, repeatDaysMask = Alarm.maskOf(days)).block()

    private val weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    // 2026-10-21 is a Wednesday.
    @Test fun `one-time alarm later today rings today`() {
        assertEquals(at(2026, 10, 21, 7, 0), calc.next(alarm(), at(2026, 10, 21, 6, 0)))
    }

    @Test fun `one-time alarm already passed rings tomorrow`() {
        assertEquals(at(2026, 10, 22, 7, 0), calc.next(alarm(), at(2026, 10, 21, 7, 30)))
    }

    @Test fun `alarm exactly at now rings next day, never twice`() {
        assertEquals(at(2026, 10, 22, 7, 0), calc.next(alarm(), at(2026, 10, 21, 7, 0)))
        assertEquals(at(2026, 10, 22, 7, 0), calc.next(alarm(), at(2026, 10, 21, 7, 0, sec = 0)))
    }

    @Test fun `disabled alarm never rings`() {
        assertNull(calc.next(alarm(enabled = false), at(2026, 10, 21, 6, 0)))
    }

    @Test fun `weekday alarm on Friday evening rings Monday`() {
        // 2026-10-23 is Friday.
        assertEquals(at(2026, 10, 26, 7, 0), calc.next(alarm(days = weekdays), at(2026, 10, 23, 8, 0)))
    }

    @Test fun `weekend alarm on a Wednesday rings Saturday`() {
        assertEquals(at(2026, 10, 24, 9, 30), calc.next(alarm(9, 30, listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)), at(2026, 10, 21, 12, 0)))
    }

    @Test fun `pinned date in the future rings then, in the past never`() {
        val future = alarm { copy(oneTimeEpochDay = LocalDate.of(2026, 12, 25).toEpochDay()) }
        assertEquals(at(2026, 12, 25, 7, 0), calc.next(future, at(2026, 10, 21, 6, 0)))
        val past = alarm { copy(oneTimeEpochDay = LocalDate.of(2026, 1, 1).toEpochDay()) }
        assertNull(calc.next(past, at(2026, 10, 21, 6, 0)))
    }

    @Test fun `skip next suppresses exactly one occurrence`() {
        val a = alarm(days = weekdays) { copy(skipEpochDay = LocalDate.of(2026, 10, 22).toEpochDay()) }
        // Wed 06:00 -> Wed 07:00 still rings; Thu is skipped.
        assertEquals(at(2026, 10, 21, 7, 0), calc.next(a, at(2026, 10, 21, 6, 0)))
        assertEquals(at(2026, 10, 23, 7, 0), calc.next(a, at(2026, 10, 21, 8, 0)))
    }

    @Test fun `exception dates are skipped for alarms that respect them`() {
        val christmas = LocalDate.of(2026, 12, 25).toEpochDay()
        val ctx = SchedulingContext(exceptionDays = setOf(christmas))
        val a = alarm(days = DayOfWeek.entries)
        assertEquals(at(2026, 12, 26, 7, 0), calc.next(a, at(2026, 12, 24, 8, 0), ctx))
        assertEquals(at(2026, 12, 25, 7, 0), calc.next(a.copy(respectExceptions = false), at(2026, 12, 24, 8, 0), ctx))
    }

    @Test fun `exceptions do not affect one-time alarms`() {
        val ctx = SchedulingContext(exceptionDays = setOf(LocalDate.of(2026, 10, 22).toEpochDay()))
        assertEquals(at(2026, 10, 22, 7, 0), calc.next(alarm(), at(2026, 10, 21, 8, 0), ctx))
    }

    @Test fun `vacation with a resume date pushes the next ring to that day`() {
        val resume = LocalDate.of(2026, 11, 2).toEpochDay()
        val ctx = SchedulingContext(vacation = VacationMode(enabled = true, resumeEpochDay = resume))
        val a = alarm(days = DayOfWeek.entries)
        assertEquals(at(2026, 11, 2, 7, 0), calc.next(a, at(2026, 10, 21, 8, 0), ctx))
    }

    @Test fun `indefinite vacation silences everything except flagged alarms`() {
        val ctx = SchedulingContext(vacation = VacationMode(enabled = true, resumeEpochDay = null))
        assertNull(calc.next(alarm(days = weekdays), at(2026, 10, 21, 6, 0), ctx))
        assertNull(calc.next(alarm(), at(2026, 10, 21, 6, 0), ctx))
        assertEquals(at(2026, 10, 21, 7, 0), calc.next(alarm { copy(ignoreVacation = true) }, at(2026, 10, 21, 6, 0), ctx))
    }

    @Test fun `disabled group silences its alarms`() {
        assertNull(calc.next(alarm(days = weekdays), at(2026, 10, 21, 6, 0), SchedulingContext(groupEnabled = false)))
    }

    @Test fun `spring-forward gap moves a nonexistent time forward instead of dropping the alarm`() {
        // New York 2026-03-08: 02:00 jumps to 03:00, so 02:30 does not exist.
        val next = calc.next(alarm(2, 30), at(2026, 3, 8, 0, 30, newYork))!!
        assertEquals(LocalDate.of(2026, 3, 8), next.toLocalDate())
        assertEquals(3, next.hour)
        assertEquals(30, next.minute)
    }

    @Test fun `fall-back overlap rings once at the first occurrence`() {
        // New York 2026-11-01: 01:30 happens twice (EDT then EST).
        val next = calc.next(alarm(1, 30), at(2026, 11, 1, 0, 0, newYork))!!
        assertEquals(java.time.ZoneOffset.ofHours(-4), next.offset)
        // After the first 01:30 has passed, the next ring is the following day, not the repeated hour.
        val after = calc.next(alarm(1, 30), next.plusMinutes(1))!!
        assertEquals(LocalDate.of(2026, 11, 2), after.toLocalDate())
    }

    @Test fun `a daily alarm keeps its wall-clock time across the DST change`() {
        val a = alarm(7, 0, DayOfWeek.entries)
        // Mar 7 07:00 EST (before the jump) and Mar 8 07:00 EDT (after it).
        val beforeChange = calc.next(a, at(2026, 3, 7, 6, 0, newYork))!!
        val afterChange = calc.next(a, at(2026, 3, 7, 8, 0, newYork))!!
        assertEquals(java.time.ZoneOffset.ofHours(-5), beforeChange.offset)
        assertEquals(java.time.ZoneOffset.ofHours(-4), afterChange.offset)
        assertEquals(7, beforeChange.hour)
        assertEquals(7, afterChange.hour)
        // 23 hours apart in absolute time because the clocks jumped, yet 07:00 both days.
        assertEquals(23, java.time.Duration.between(beforeChange, afterChange).toHours())
    }

    @Test fun `the same alarm lands on different instants in different zones`() {
        val a = alarm(7, 0)
        val tokyo = calc.next(a, at(2026, 10, 21, 0, 0, ZoneId.of("Asia/Tokyo")))!!
        val la = calc.next(a, at(2026, 10, 21, 0, 0, ZoneId.of("America/Los_Angeles")))!!
        assertNotEquals(tokyo.toInstant(), la.toInstant())
        assertEquals(7, tokyo.hour)
        assertEquals(7, la.hour)
    }

    @Test fun `upcoming lists consecutive occurrences`() {
        val list = calc.upcoming(alarm(days = weekdays), at(2026, 10, 23, 8, 0), count = 3)
        assertEquals(listOf(26, 27, 28), list.map { it.dayOfMonth })
    }

    @Test fun `leap day and far-future gaps are found within the horizon`() {
        val a = alarm(days = listOf(DayOfWeek.MONDAY))
        val ctx = SchedulingContext(exceptionDays = (0..60).map { LocalDate.of(2026, 10, 21).plusDays(it.toLong()).toEpochDay() }.toSet())
        val next = calc.next(a, at(2026, 10, 21, 8, 0), ctx)!!
        assertTrue(next.toLocalDate().isAfter(LocalDate.of(2026, 12, 20)))
        assertEquals(DayOfWeek.MONDAY, next.dayOfWeek)
    }

    @Test fun `suppression explains why the natural next ring is skipped`() {
        val christmas = LocalDate.of(2026, 12, 25)
        val ctx = SchedulingContext(exceptionDays = setOf(christmas.toEpochDay()))
        val a = alarm(days = DayOfWeek.entries)
        assertEquals(Suppression.Exception(christmas), calc.suppressionOfNaturalNext(a, at(2026, 12, 24, 8, 0), ctx))
        assertNull(calc.suppressionOfNaturalNext(a, at(2026, 12, 20, 8, 0), ctx))

        val skipped = a.copy(skipEpochDay = LocalDate.of(2026, 10, 22).toEpochDay())
        assertEquals(Suppression.SkippedOnce(LocalDate.of(2026, 10, 22)), calc.suppressionOfNaturalNext(skipped, at(2026, 10, 21, 8, 0), SchedulingContext()))
    }
}
