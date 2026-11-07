package com.riyadm.wakenudge.domain

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.domain.scheduling.MissionRotation
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionRotationTest {
    private val mixed = listOf(MissionConfig(MissionType.FOCUS_TAP), MissionConfig(MissionType.MEMORY), MissionConfig(MissionType.MATH))
    private val two = mixed.take(2)
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(date: LocalDate, zone: ZoneId = berlin, time: LocalTime = LocalTime.of(7, 0)) = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    private fun picks(missions: List<MissionConfig>, days: List<LocalDate>, zone: ZoneId = berlin, mask: Int = Alarm.EVERY_DAY_MASK) =
        days.map { MissionRotation.pick(missions, at(it, zone), 0, zone, mask)!! }

    @Test fun `over a month no two days in a row ask for the same mission, and each takes its turn`() {
        for (missions in listOf(two, mixed)) {
            val month = (0L until 31).map { LocalDate.of(2026, 3, 1).plusDays(it) } // across the spring change in Europe
            val picked = picks(missions, month)
            picked.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
            for (m in missions) assertTrue("${m.type} gets its turns", picked.count { it == m } >= 31 / missions.size)
        }
    }

    @Test fun `a daylight-saving change does not repeat or skip a day, in either direction`() {
        for (zone in listOf(berlin, ZoneId.of("America/New_York"), ZoneId.of("Australia/Sydney"))) {
            for (start in listOf(LocalDate.of(2026, 3, 25), LocalDate.of(2026, 10, 22), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))) {
                val days = (0L until 10).map { start.plusDays(it) }
                // Late at night, where the clock change moves the hour across midnight if anything does.
                val picked = days.map { MissionRotation.pick(mixed, at(it, zone, LocalTime.of(23, 30)), 0, zone)!! }
                picked.zipWithNext().forEach { (a, b) -> assertNotEquals("$zone from $start", a, b) }
            }
        }
    }

    @Test fun `the same occurrence always gets the same mission, a re-ring moves on to the next`() {
        val scheduled = at(LocalDate.of(2026, 5, 4))
        val first = MissionRotation.pick(mixed, scheduled, 0, berlin)
        assertEquals(first, MissionRotation.pick(mixed, scheduled, 0, berlin))
        val second = MissionRotation.pick(mixed, scheduled, 1, berlin)
        val third = MissionRotation.pick(mixed, scheduled, 2, berlin)
        assertEquals(setOf(first, second, third), mixed.toSet())
    }

    @Test fun `an alarm on some days turns through its missions like a daily one`() {
        val mwf = Alarm.maskOf(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY))
        val calc = NextTriggerCalculator()
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDaysMask = mwf)
        var from = ZonedDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.NOON, berlin)
        val rings = (0 until 12).map { calc.next(alarm, from)!!.also { from = it.plusMinutes(1) } }
        val picked = rings.map { MissionRotation.pick(two, it.toInstant().toEpochMilli(), 0, berlin, mwf)!! }
        picked.zipWithNext().forEach { (a, b) -> assertNotEquals("Monday, Wednesday, Friday alternate", a, b) }
    }

    @Test fun `a one-time alarm counts days`() {
        val days = (0L until 4).map { LocalDate.of(2026, 7, 1).plusDays(it) }
        picks(two, days, mask = 0).zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
    }

    @Test fun `the alarm for one ring holds the one mission only when it changes each morning and has a choice`() {
        val scheduled = at(LocalDate.of(2026, 5, 4))
        val rotating = Alarm(id = 1, missions = mixed, antiOversleep = AntiOversleepSettings(rotateMissions = true))
        assertEquals(1, MissionRotation.forRing(rotating, scheduled, 0, berlin).missions.size)
        assertEquals("all of them in turn without the switch", mixed, MissionRotation.forRing(rotating.copy(antiOversleep = AntiOversleepSettings()), scheduled, 0, berlin).missions)
        val single = rotating.copy(missions = mixed.take(1))
        assertEquals(single, MissionRotation.forRing(single, scheduled, 0, berlin))
        assertNull(MissionRotation.pick(emptyList(), scheduled, 0, berlin))
    }
}
