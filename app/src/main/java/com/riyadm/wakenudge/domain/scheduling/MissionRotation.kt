package com.riyadm.wakenudge.domain.scheduling

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.MissionConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Which one of an alarm's missions a ring asks for when it changes each morning. A single mission becomes a habit within
 * weeks, so each occurrence takes the next one in turn, and the same occurrence always gets the same one: a snooze coming
 * back, a backup or a restart asks for what the ring asked for. Each re-ring of a still-awake check moves on to the next.
 *
 * Counted in the alarm's own ring days, not calendar days, so an alarm on Monday, Wednesday and Friday turns through its
 * missions like a daily one does. Pure.
 */
object MissionRotation {
    private val epoch: LocalDate = LocalDate.of(2024, 1, 1) // a Monday

    fun pick(missions: List<MissionConfig>, scheduledAt: Long, reRings: Int, zone: ZoneId, repeatDaysMask: Int = Alarm.EVERY_DAY_MASK): MissionConfig? {
        if (missions.isEmpty()) return null
        val day = Instant.ofEpochMilli(scheduledAt).atZone(zone).toLocalDate()
        val turn = occurrence(day, repeatDaysMask) + reRings.coerceAtLeast(0)
        return missions[Math.floorMod(turn, missions.size.toLong()).toInt()]
    }

    /** How many of the alarm's ring days fall between [epoch] and [day]; every day for a one-time alarm. */
    internal fun occurrence(day: LocalDate, repeatDaysMask: Int): Long {
        val mask = repeatDaysMask and Alarm.EVERY_DAY_MASK
        val days = ChronoUnit.DAYS.between(epoch, day)
        if (mask == 0 || mask == Alarm.EVERY_DAY_MASK) return days
        val perWeek = Integer.bitCount(mask)
        val weeks = Math.floorDiv(days, 7L)
        val into = Math.floorMod(days, 7L).toInt()
        // Bit 0 is Monday, as in the rest of the app, and the epoch is a Monday: day `into` of a week is bit `into`.
        val before = (0 until into).count { mask and (1 shl it) != 0 }
        return weeks * perWeek + before
    }

    /** The alarm as one ring of it asks for it: one mission if it changes each morning and has a choice, otherwise as it is. */
    fun forRing(alarm: Alarm, scheduledAt: Long, reRings: Int, zone: ZoneId): Alarm {
        if (!alarm.antiOversleep.rotateMissions || alarm.missions.size < 2) return alarm
        val picked = pick(alarm.missions, scheduledAt, reRings, zone, alarm.repeatDaysMask) ?: return alarm
        return alarm.copy(missions = listOf(picked))
    }
}
