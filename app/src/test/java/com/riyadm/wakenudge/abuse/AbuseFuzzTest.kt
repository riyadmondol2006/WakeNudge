package com.riyadm.wakenudge.abuse

import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.data.backup.BackupRig
import com.riyadm.wakenudge.data.backup.ImportResult
import com.riyadm.wakenudge.data.backup.Sample
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.VacationMode
import com.riyadm.wakenudge.domain.model.VibrationPattern
import com.riyadm.wakenudge.domain.model.VibrationSettings
import com.riyadm.wakenudge.domain.model.VolumeSettings
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.scheduling.SchedulingContext
import com.riyadm.wakenudge.domain.stats.WakeStatsCalculator
import com.riyadm.wakenudge.engine.audio.VibrationPatterns
import com.riyadm.wakenudge.engine.audio.VolumePlanner
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Throws chaos at the pure logic. Every test is seeded (so failures reproduce) and asserts invariants, not examples:
 * whatever a user, a corrupted file or a travelling clock does, nothing may throw and the answers must stay sane.
 */
class AbuseFuzzTest {

    // Includes 30- and 45-minute offsets, southern-hemisphere DST, a zone that skips a whole calendar day (Samoa 2011
    // is in the past, but Pacific/Apia still has unusual history) and zones with midnight DST transitions.
    private val zones = listOf(
        "UTC", "America/New_York", "America/Sao_Paulo", "Europe/London", "Europe/Berlin", "Africa/Cairo", "Asia/Kolkata",
        "Asia/Kathmandu", "Asia/Dhaka", "Asia/Tokyo", "Australia/Lord_Howe", "Australia/Sydney", "Pacific/Chatham",
        "Pacific/Apia", "Pacific/Kiritimati", "America/St_Johns", "Asia/Tehran", "America/Havana", "America/Santiago",
    ).map(ZoneId::of)

    private val calc = NextTriggerCalculator()

    private fun randomAlarm(r: Random, today: LocalDate): Alarm {
        val repeating = r.nextBoolean()
        return Alarm(
            id = r.nextLong(1, 1000),
            hour = r.nextInt(0, 24), minute = r.nextInt(0, 60),
            repeatDaysMask = if (repeating) r.nextInt(1, 128) else 0,
            oneTimeEpochDay = if (!repeating && r.nextInt(4) == 0) today.toEpochDay() + r.nextLong(-30, 400) else null,
            skipEpochDay = if (repeating && r.nextInt(3) == 0) today.toEpochDay() + r.nextLong(-3, 14) else null,
            respectExceptions = r.nextBoolean(), ignoreVacation = r.nextInt(4) == 0,
        )
    }

    private fun randomContext(r: Random, today: LocalDate): SchedulingContext {
        val ex = if (r.nextInt(3) == 0) (0 until r.nextInt(0, 500)).map { today.toEpochDay() + r.nextLong(-5, 450) }.toSet() else emptySet()
        val vacation = when (r.nextInt(4)) {
            0 -> VacationMode(true, null)
            1 -> VacationMode(true, today.toEpochDay() + r.nextLong(-20, 60))
            else -> VacationMode(false, null)
        }
        return SchedulingContext(vacation, ex, groupEnabled = r.nextInt(8) != 0)
    }

    @Test fun nextTriggerNeverThrowsAndAlwaysObeysTheRules() {
        val r = Random(20261021)
        repeat(20_000) { n ->
            val zone = zones[r.nextInt(zones.size)]
            val now = ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, zone).plusSeconds(r.nextLong(0, 4 * 365L * 86_400))
            val alarm = randomAlarm(r, now.toLocalDate())
            val ctx = randomContext(r, now.toLocalDate())
            val next = calc.next(alarm, now, ctx)
            val label = "case $n: $alarm in $zone at $now ctx=$ctx -> $next"
            if (next == null) return@repeat

            assertTrue("must be in the future: $label", next.isAfter(now))
            assertTrue("within the search horizon: $label", Duration.between(now, next).toDays() <= 402 + 365)
            assertEquals("zone preserved: $label", zone, next.zone)
            // Wall clock time is kept, except when that time does not exist (DST gap), where it moves forward only.
            val wanted = alarm.time
            val got = next.toLocalTime()
            assertTrue("wall time kept or pushed past a DST gap: $label", got == wanted || got.isAfter(wanted) || got.isBefore(wanted.plusHours(3)))
            val date = next.toLocalDate()
            if (alarm.isRepeating) assertTrue("weekday must be selected: $label", alarm.repeatsOn(date.dayOfWeek))
            if (alarm.isRepeating && alarm.respectExceptions) assertFalse("exception day respected: $label", date.toEpochDay() in ctx.exceptionDays)
            if (alarm.isRepeating) assertFalse("skipped day respected: $label", alarm.skipEpochDay == date.toEpochDay())
            if (ctx.vacation.enabled && !alarm.ignoreVacation) {
                assertTrue("vacation must end first: $label", date.toEpochDay() >= (ctx.vacation.resumeEpochDay ?: Long.MAX_VALUE))
            }
            assertTrue("group switch respected: $label", ctx.groupEnabled)
        }
    }

    @Test fun upcomingListIsStrictlyIncreasingAndNeverLoopsForever() {
        val r = Random(7)
        repeat(3_000) {
            val zone = zones[r.nextInt(zones.size)]
            val now = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).plusSeconds(r.nextLong(0, 365L * 86_400))
            val alarm = randomAlarm(r, now.toLocalDate()).copy(repeatDaysMask = r.nextInt(1, 128))
            val list = calc.upcoming(alarm, now, randomContext(r, now.toLocalDate()), count = 6)
            list.zipWithNext().forEach { (a, b) -> assertTrue("increasing: $alarm", b.isAfter(a)) }
        }
    }

    @Test fun anAlarmWithEveryDayBlockedReturnsNullInsteadOfSpinning() {
        val now = ZonedDateTime.of(2026, 3, 1, 8, 0, 0, 0, ZoneId.of("UTC"))
        val blocked = (0..420L).map { now.toLocalDate().toEpochDay() + it }.toSet()
        val start = System.nanoTime()
        assertEquals(null, calc.next(Alarm(repeatDaysMask = Alarm.EVERY_DAY_MASK), now, SchedulingContext(exceptionDays = blocked)))
        assertTrue("bounded work", (System.nanoTime() - start) < 2_000_000_000L)
    }

    @Test fun midnightDstGapsStillRing() {
        // São Paulo used to switch at midnight (00:00 -> 01:00), so an alarm at 00:30 does not exist that night.
        val zone = ZoneId.of("America/Sao_Paulo")
        val now = ZonedDateTime.of(2018, 11, 3, 12, 0, 0, 0, zone)
        val next = calc.next(Alarm(hour = 0, minute = 30, repeatDaysMask = Alarm.EVERY_DAY_MASK), now)!!
        assertEquals(java.time.LocalDate.of(2018, 11, 4), next.toLocalDate())
        assertTrue("rings after the gap, not before", next.toLocalTime() >= java.time.LocalTime.of(1, 0))
    }

    @Test fun halfAndQuarterHourZonesKeepTheirWallClock() {
        for (id in listOf("Asia/Kathmandu", "Asia/Kolkata", "Pacific/Chatham", "Australia/Lord_Howe")) {
            val zone = ZoneId.of(id)
            repeat(50) {
                val now = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, zone).plusHours(it * 173L)
                val next = calc.next(Alarm(hour = 5, minute = 15, repeatDaysMask = Alarm.EVERY_DAY_MASK), now)!!
                assertEquals(id, 5, next.hour)
                assertEquals(id, 15, next.minute)
            }
        }
    }

    @Test fun leapDayAndYearBoundariesAreNotSpecial() {
        val zone = ZoneId.of("UTC")
        val feb28 = ZonedDateTime.of(2028, 2, 28, 23, 0, 0, 0, zone) // 2028 is a leap year
        assertEquals(java.time.LocalDate.of(2028, 2, 29), calc.next(Alarm(hour = 7, repeatDaysMask = Alarm.EVERY_DAY_MASK), feb28)!!.toLocalDate())
        val nye = ZonedDateTime.of(2026, 12, 31, 23, 30, 0, 0, zone)
        assertEquals(java.time.LocalDate.of(2027, 1, 1), calc.next(Alarm(hour = 0, minute = 5, repeatDaysMask = Alarm.EVERY_DAY_MASK), nye)!!.toLocalDate())
    }

    // ---- audio safety ------------------------------------------------------------------------------------------

    @Test fun hearingProtectionHoldsForEveryCombination() {
        val r = Random(99)
        repeat(20_000) {
            val v = VolumeSettings(
                level = r.nextInt(0, 101), extraLoud = r.nextBoolean(), extraLoudPercent = r.nextInt(-50, 500), useMaxSystemVolume = r.nextBoolean(), escalateWhenIgnored = r.nextBoolean(),
                rampSeconds = r.nextInt(0, 700), rampStartPercent = r.nextInt(0, 101),
            )
            val max = r.nextInt(1, 31)
            val capPercent = r.nextInt(0, 140)
            val plan = VolumePlanner.plan(v, max, r.nextInt(0, max + 1), headsetRoute = r.nextBoolean(), hearingCapEnabled = true, capPercent = capPercent, escalated = r.nextBoolean(), sourceLufs = if (r.nextBoolean()) -r.nextDouble(8.0, 40.0) else null)
            plan.streamIndex?.let { assertTrue("index within the stream: $plan", it in 1..max) }
            assertTrue("boost bounded: $plan", plan.enhancerMillibels in 0..VolumePlanner.MAX_ENHANCER_MB)
        }
        // And the actual guarantee: on headphones with the cap on, nothing exceeds the cap nor gets boosted.
        repeat(5_000) {
            val max = r.nextInt(2, 31)
            val cap = r.nextInt(10, 101)
            val v = VolumeSettings(level = r.nextInt(0, 101), extraLoud = true, extraLoudPercent = r.nextInt(-50, 500), useMaxSystemVolume = r.nextBoolean(), escalateWhenIgnored = true)
            val plan = VolumePlanner.plan(v, max, r.nextInt(0, max + 1), headsetRoute = true, hearingCapEnabled = true, capPercent = cap, escalated = r.nextBoolean(), sourceLufs = -20.0)
            val ceiling = VolumePlanner.percentToIndex(cap.coerceIn(10, 100), max)
            plan.streamIndex?.let { assertTrue("capped at $ceiling: $plan (cap $cap, max $max)", it <= ceiling) }
            assertEquals("no boost on headphones: $plan", 0, plan.enhancerMillibels)
        }
    }

    @Test fun rampGainIsAlwaysAUsableVolume() {
        val r = Random(5)
        repeat(10_000) {
            val g = VolumePlanner.rampGain(r.nextLong(-5_000, 2_000_000), r.nextInt(-5, 900), r.nextInt(-50, 200))
            assertTrue("gain $g", g in 0f..1f && !g.isNaN())
        }
    }

    @Test fun anyVibrationPatternBuildsAValidWaveform() {
        val r = Random(11)
        repeat(5_000) {
            val settings = VibrationSettings(
                enabled = true, pattern = VibrationPattern.entries[r.nextInt(VibrationPattern.entries.size)], strength = r.nextInt(-20, 400),
                customTimings = List(r.nextInt(0, 90)) { r.nextLong(-5_000, 90_000) },
            )
            val w = VibrationPatterns.waveform(settings, r.nextBoolean())
            assertEquals(w.timings.size, w.amplitudes.size)
            assertTrue("no negative timing", w.timings.all { it >= 0 })
            assertTrue("amplitudes in range", w.amplitudes.all { it in 0..255 })
            assertTrue("something actually vibrates", w.amplitudes.indices.any { w.amplitudes[it] > 0 && w.timings[it] > 0 })
        }
    }

    // ---- hostile data ------------------------------------------------------------------------------------------

    @Test fun historyWithTimeTravelNeverBreaksTheStats() {
        val r = Random(3)
        repeat(2_000) {
            val events = List(r.nextInt(0, 60)) {
                val fired = r.nextLong(0, 4_000_000_000_000L)
                AlarmEvent(
                    id = it.toLong(), alarmId = 1, label = "x".repeat(r.nextInt(0, 200)), scheduledAt = r.nextLong(-1_000, 4_000_000_000_000L),
                    firedAt = fired, endedAt = if (r.nextBoolean()) fired + r.nextLong(-86_400_000L, 86_400_000L) else null,
                    outcome = AlarmOutcome.entries[r.nextInt(AlarmOutcome.entries.size)], snoozeCount = r.nextInt(0, 500),
                    wakeCheck = WakeCheckStatus.entries[r.nextInt(WakeCheckStatus.entries.size)],
                )
            }
            val stats = WakeStatsCalculator.compute(events, zones[r.nextInt(zones.size)])
            assertTrue(stats.currentStreak <= stats.bestStreak)
            assertTrue(stats.successRate in 0f..1f)
            stats.averageDismissMs?.let { assertTrue("never negative", it >= 0) }
        }
    }

    @Test fun durationTextSurvivesAbsurdNumbers() {
        listOf(Long.MIN_VALUE / 2, -1L, 0L, 999L, 86_400_000L * 400, Long.MAX_VALUE / 4).forEach { ms ->
            assertNotNull(TimeText.duration(ms))
            assertNotNull(TimeText.clockDuration(ms, withHundredths = true))
        }
    }

    @Test fun corruptedBackupFilesNeverCrashTheRestore() = runTest {
        val rig = BackupRig(Sample.file)
        val valid = rig.manager.export(includeHistory = true)
        val r = Random(2026)
        var rejected = 0
        var accepted = 0
        repeat(1_500) {
            val mutated = StringBuilder(valid)
            repeat(r.nextInt(1, 12)) {
                when (r.nextInt(5)) {
                    0 -> if (mutated.isNotEmpty()) mutated.setCharAt(r.nextInt(mutated.length), "{}[]\":,0123456789-nulltrue\\ \n".random(r))
                    1 -> if (mutated.isNotEmpty()) mutated.setLength(r.nextInt(1, mutated.length + 1))
                    2 -> mutated.insert(r.nextInt(mutated.length + 1), listOf("999999999999999999999", "-1", "null", "\"\"", "[]", "{}", "1e999", "😀").random(r))
                    3 -> if (mutated.isNotEmpty()) mutated.deleteCharAt(r.nextInt(mutated.length))
                    else -> mutated.insert(r.nextInt(mutated.length + 1), "\u0000￿\uD800")
                }
            }
            val fresh = BackupRig()
            when (val result = fresh.manager.import(mutated.toString())) {
                is ImportResult.Failure -> rejected++
                is ImportResult.Success -> {
                    accepted++
                    // Whatever survived must be valid enough that scheduling cannot blow up later.
                    fresh.store.contents.alarms.forEach { alarm ->
                        assertTrue("hour ${alarm.hour}", alarm.hour in 0..23)
                        assertTrue("minute ${alarm.minute}", alarm.minute in 0..59)
                        assertTrue(alarm.volume.level in 0..100)
                        assertTrue(alarm.snooze.minutes in 1..60)
                        assertTrue(alarm.label.length <= 60)
                        calc.next(alarm, ZonedDateTime.now(ZoneId.of("UTC")))
                    }
                }
            }
        }
        assertTrue("fuzzer produced both outcomes (rejected=$rejected accepted=$accepted)", rejected > 0 && accepted > 0)
    }
}
