package com.riyadm.wakenudge.domain.scheduling

import java.time.LocalTime

data class SleepOption(
    val cycles: Int,
    val sleepMinutes: Int,
    /** The time to be in bed (when planning from a wake time) or to wake up (when planning from a bedtime). */
    val time: LocalTime,
)

/** Sleep-cycle maths: a cycle lasts about 90 minutes; waking at a cycle boundary feels less groggy. */
object SleepPlanner {
    const val CYCLE_MINUTES = 90
    val DEFAULT_CYCLES = listOf(6, 5, 4)

    fun bedtimesFor(
        wakeTime: LocalTime,
        fallAsleepMinutes: Int = 15,
        cycles: List<Int> = DEFAULT_CYCLES,
    ): List<SleepOption> = cycles.map { n ->
        val sleep = n * CYCLE_MINUTES
        SleepOption(n, sleep, wakeTime.minusMinutes((sleep + fallAsleepMinutes).toLong()))
    }

    fun wakeTimesFor(
        bedtime: LocalTime,
        fallAsleepMinutes: Int = 15,
        cycles: List<Int> = DEFAULT_CYCLES,
    ): List<SleepOption> = cycles.map { n ->
        val sleep = n * CYCLE_MINUTES
        SleepOption(n, sleep, bedtime.plusMinutes((sleep + fallAsleepMinutes).toLong()))
    }

    /** Bedtime that hits an explicit sleep goal in minutes (not rounded to cycles). */
    fun bedtimeForGoal(wakeTime: LocalTime, goalMinutes: Int, fallAsleepMinutes: Int): LocalTime =
        wakeTime.minusMinutes((goalMinutes + fallAsleepMinutes).toLong())

    fun reminderTime(bedtime: LocalTime, leadMinutes: Int): LocalTime = bedtime.minusMinutes(leadMinutes.toLong())
}
