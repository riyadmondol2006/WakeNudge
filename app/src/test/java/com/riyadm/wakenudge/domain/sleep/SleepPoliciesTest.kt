package com.riyadm.wakenudge.domain.sleep

import com.riyadm.wakenudge.domain.model.AlarmOutcome
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepPoliciesTest {
    private val min = 60_000L

    // ---- clips ---------------------------------------------------------------------------------------------------

    @Test fun `the first three spread out clips are kept`() {
        assertEquals(ClipDecision.Add, ClipPolicy.decide(emptyList(), 0, -40f))
        val two = listOf(ClipRef(1, 0, -40f), ClipRef(2, 10 * min, -45f))
        assertEquals(ClipDecision.Add, ClipPolicy.decide(two, 20 * min, -50f))
    }

    @Test fun `a fourth clip replaces the quietest only when louder`() {
        val three = listOf(ClipRef(1, 0, -40f), ClipRef(2, 10 * min, -45f), ClipRef(3, 20 * min, -35f))
        assertEquals(ClipDecision.Replace(2), ClipPolicy.decide(three, 40 * min, -42f))
        assertEquals(ClipDecision.Drop, ClipPolicy.decide(three, 40 * min, -46f))
    }

    @Test fun `clips close together are not three copies of the same minute`() {
        val one = listOf(ClipRef(1, 0, -40f))
        assertEquals(ClipDecision.Drop, ClipPolicy.decide(one, 2 * min, -45f))
        assertEquals(ClipDecision.Replace(1), ClipPolicy.decide(one, 2 * min, -30f))
    }

    @Test fun `clips expire after seven days, and a clip from the future is not trusted`() {
        val now = 100L * 24 * 60 * min
        assertFalse(ClipPolicy.expired(now - SleepLimits.CLIP_RETENTION_MS + 1, now))
        assertTrue(ClipPolicy.expired(now - SleepLimits.CLIP_RETENTION_MS, now))
        assertTrue(ClipPolicy.expired(now + 2 * 24 * 60 * min, now))
    }

    @Test fun `loudness is judged against the room`() {
        assertEquals(ClipPolicy.Loudness.SOFT, ClipPolicy.loudness(10f))
        assertEquals(ClipPolicy.Loudness.LOUD, ClipPolicy.loudness(15f))
        assertEquals(ClipPolicy.Loudness.VERY_LOUD, ClipPolicy.loudness(30f))
    }

    // ---- rings ---------------------------------------------------------------------------------------------------

    private val alarm = RingWatchPolicy.SeenRing(eventId = 5, isTimer = false, isTest = false)

    @Test fun `a dismissed alarm ends the night, a snoozed or missed one does not`() {
        assertTrue(RingWatchPolicy.endsSession(alarm, AlarmOutcome.DISMISSED, 0, historyReadable = true))
        assertTrue(RingWatchPolicy.endsSession(alarm, AlarmOutcome.EMERGENCY_STOPPED, 0, historyReadable = true))
        assertFalse(RingWatchPolicy.endsSession(alarm, AlarmOutcome.RINGING, snoozeUntil = 123, historyReadable = true))
        assertFalse(RingWatchPolicy.endsSession(alarm, AlarmOutcome.MISSED, 0, historyReadable = true))
    }

    @Test fun `a test alarm ends the night when it goes, a timer never does`() {
        assertTrue(RingWatchPolicy.endsSession(RingWatchPolicy.SeenRing(-1, isTimer = false, isTest = true), null, 0, historyReadable = true))
        assertFalse(RingWatchPolicy.endsSession(RingWatchPolicy.SeenRing(-1, isTimer = true, isTest = false), null, 0, historyReadable = false))
    }

    @Test fun `unreadable history counts as dismissed`() {
        assertTrue(RingWatchPolicy.endsSession(alarm, null, 0, historyReadable = false))
    }

    @Test fun `every ring stops the microphone`() {
        assertTrue(RingWatchPolicy.stopsMicrophone(ringing = true))
        assertFalse(RingWatchPolicy.stopsMicrophone(ringing = false))
    }

    // ---- sessions ------------------------------------------------------------------------------------------------

    @Test fun `a session ends itself after sixteen hours`() {
        assertFalse(SessionRules.reachedLimit(0, SleepLimits.MAX_SESSION_MS - 1))
        assertTrue(SessionRules.reachedLimit(0, SleepLimits.MAX_SESSION_MS))
    }

    @Test fun `a session left open closes after its last epoch, within the limit`() {
        assertEquals(1_000L, SessionRules.closeStale(1_000, null))
        assertEquals(1_000L + 10 * SleepLimits.EPOCH_MS, SessionRules.closeStale(1_000, 9))
        assertEquals(1_000L + SleepLimits.MAX_SESSION_MS, SessionRules.closeStale(1_000, 10_000))
    }

    @Test fun `under an hour is short`() {
        assertTrue(SessionRules.isShort(0, 59 * min))
        assertFalse(SessionRules.isShort(0, 60 * min))
    }

    // ---- weeks ---------------------------------------------------------------------------------------------------

    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(date: String, hour: Int) = LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun summary(asleep: Long, latency: Long?, short: Boolean = false, verdict: NightVerdict = NightVerdict.OK) =
        NightSummary(verdict, emptyList(), asleep + 10 * min, asleep, latency, 5 * min, asleep / 4, asleep / 2, asleep / 4, 10 * min, short)

    @Test fun `weeks start on the chosen day and stop at this week`() {
        val tuesday = LocalDate.parse("2026-11-24")
        assertEquals(LocalDate.parse("2026-11-23"), SleepWeeks.weekStart(tuesday, DayOfWeek.MONDAY))
        assertEquals(LocalDate.parse("2026-11-22"), SleepWeeks.weekStart(tuesday, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.parse("2026-11-21"), SleepWeeks.weekStart(tuesday, DayOfWeek.SATURDAY))
        assertFalse(SleepWeeks.canGoForward(LocalDate.parse("2026-11-23"), tuesday, DayOfWeek.MONDAY))
        assertTrue(SleepWeeks.canGoForward(LocalDate.parse("2026-11-16"), tuesday, DayOfWeek.MONDAY))
    }

    @Test fun `a night belongs to the morning it ended, and the longest real night takes the day`() {
        val night = Night(1, at("2026-11-23", 23), at("2026-11-24", 7), summary(7 * 60 * min, 15 * min), false)
        val nap = Night(2, at("2026-11-24", 14), at("2026-11-24", 14) + 40 * min, summary(30 * min, 5 * min, short = true), false)
        val main = SleepWeeks.mainNights(listOf(night, nap), LocalDate.parse("2026-11-23"), zone)
        assertEquals(setOf(LocalDate.parse("2026-11-24")), main.keys)
        assertEquals(1L, main.values.single().sessionId)
    }

    @Test fun `a night outside the week is not shown in it`() {
        val night = Night(1, at("2026-11-29", 23), at("2026-11-30", 7), summary(7 * 60 * min, 15 * min), false)
        assertTrue(SleepWeeks.mainNights(listOf(night), LocalDate.parse("2026-11-23"), zone).isEmpty())
    }

    @Test fun `averages skip short nights and nights without an estimate`() {
        val a = Night(1, 0, 8 * 60 * min, summary(7 * 60 * min, 10 * min), false)
        val b = Night(2, 0, 8 * 60 * min, summary(6 * 60 * min, 20 * min), false)
        val nap = Night(3, 0, 40 * min, summary(30 * min, 5 * min, short = true), false)
        val empty = Night(4, 0, 8 * 60 * min, NightSummary.none(NightVerdict.TOO_LITTLE_DATA, 8 * 60 * min, false), false)
        val avg = SleepWeeks.averages(listOf(a, b, nap, empty))
        assertEquals(2, avg.nights)
        assertEquals(390 * min, avg.asleepMs)
        assertEquals(15 * min, avg.latencyMs)
        val none = SleepWeeks.averages(listOf(nap, empty))
        assertEquals(0, none.nights)
        assertNull(none.asleepMs)
    }
}
