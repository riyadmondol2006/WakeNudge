package com.riyadm.wakenudge.widget

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceRefreshPolicyTest {

    // ---- the single midnight alarm that replaces the platform's periodic widget update ----------------------------

    private val utc = ZoneId.of("UTC")

    @Test fun `the rollover is just after the next local midnight`() {
        val now = ZonedDateTime.of(2026, 10, 21, 16, 26, 30, 0, utc)
        assertEquals(ZonedDateTime.of(2026, 10, 22, 0, 0, 2, 0, utc), SurfaceRollover.nextRollover(now))
    }

    @Test fun `the rollover from just before midnight is the coming midnight, not the one after`() {
        val now = ZonedDateTime.of(2026, 10, 21, 23, 59, 59, 0, utc)
        assertEquals(ZonedDateTime.of(2026, 10, 22, 0, 0, 2, 0, utc), SurfaceRollover.nextRollover(now))
    }

    @Test fun `exactly at midnight the rollover is a day away`() {
        val now = ZonedDateTime.of(2026, 10, 22, 0, 0, 0, 0, utc)
        assertEquals(ZonedDateTime.of(2026, 10, 23, 0, 0, 2, 0, utc), SurfaceRollover.nextRollover(now))
    }

    @Test fun `the rollover follows the zone the phone is in, not UTC`() {
        val dhaka = ZoneId.of("Asia/Dhaka")
        val now = ZonedDateTime.of(2026, 10, 21, 22, 0, 0, 0, dhaka)
        assertEquals(ZonedDateTime.of(2026, 10, 22, 0, 0, 2, 0, dhaka), SurfaceRollover.nextRollover(now))
    }

    @Test fun `a day shorter or longer because of daylight saving still ends at local midnight`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 25 hours long: clocks go back on 2026-10-25, so one o'clock to the next midnight is 24 real hours.
        val longDay = ZonedDateTime.of(2026, 10, 25, 1, 0, 0, 0, berlin)
        val longEnd = SurfaceRollover.nextRollover(longDay)
        assertEquals(LocalDateTime.of(2026, 10, 26, 0, 0, 2), longEnd.toLocalDateTime())
        assertEquals(24L, Duration.between(longDay, longEnd.minusSeconds(2)).toHours())
        // 23 hours long: clocks go forward on 2026-03-29, so one o'clock to the next midnight is 22 real hours.
        val shortDay = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, berlin)
        val shortEnd = SurfaceRollover.nextRollover(shortDay)
        assertEquals(LocalDateTime.of(2026, 3, 30, 0, 0, 2), shortEnd.toLocalDateTime())
        assertEquals(22L, Duration.between(shortDay, shortEnd.minusSeconds(2)).toHours())
    }

    @Test fun `when midnight does not exist the rollover lands on the first moment of the day`() {
        // Some zones skip 00:00 on the day clocks go forward (Havana: 00:00 -> 01:00 on 2026-03-08).
        val havana = ZoneId.of("America/Havana")
        val now = ZonedDateTime.of(2026, 3, 7, 20, 0, 0, 0, havana)
        val next = SurfaceRollover.nextRollover(now)
        assertEquals(8, next.dayOfMonth)
        assertTrue(next.isAfter(now))
        assertEquals(1, next.hour)
    }

    // ---- the "has this widget already been given this picture?" ledger ----------------------------------------------

    private val hour = 60 * 60_000L

    @Test fun `a picture that was never recorded is not current`() {
        assertFalse(RenderLedger(hour).isCurrent("w", "picture", 0))
    }

    @Test fun `the same picture is current, a different one is not`() {
        val ledger = RenderLedger(hour)
        ledger.record("w", "a", 1_000)
        assertTrue(ledger.isCurrent("w", "a", 2_000))
        assertFalse(ledger.isCurrent("w", "b", 2_000))
    }

    @Test fun `widgets are tracked independently`() {
        val ledger = RenderLedger(hour)
        ledger.record("one", "a", 0)
        assertFalse(ledger.isCurrent("two", "a", 0))
    }

    @Test fun `a picture is only trusted for the maximum age, so a failed redraw heals`() {
        val ledger = RenderLedger(hour)
        ledger.record("w", "a", 0)
        assertTrue(ledger.isCurrent("w", "a", hour))
        assertFalse(ledger.isCurrent("w", "a", hour + 1))
    }

    @Test fun `a clock set back is never trusted`() {
        val ledger = RenderLedger(hour)
        ledger.record("w", "a", 10_000)
        assertFalse(ledger.isCurrent("w", "a", 9_000))
    }

    @Test fun `forgetting a widget makes its next picture new again`() {
        val ledger = RenderLedger(hour)
        ledger.record("w", "a", 0)
        ledger.forget("w")
        assertFalse(ledger.isCurrent("w", "a", 1))
    }

    @Test fun `recording replaces the previous picture`() {
        val ledger = RenderLedger(hour)
        ledger.record("w", "a", 0)
        ledger.record("w", "b", 5)
        assertFalse(ledger.isCurrent("w", "a", 6))
        assertTrue(ledger.isCurrent("w", "b", 6))
    }
}
