package com.riyadm.wakenudge.domain.stats

import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DaySummary(
    val date: LocalDate,
    val events: List<AlarmEvent>,
) {
    val successful: Boolean get() = events.isNotEmpty() && events.all { it.isSuccessfulWakeUp }
    val snoozes: Int get() = events.sumOf { it.snoozeCount }
    val dismissDurationMs: Long? get() = events.mapNotNull { it.dismissDurationMs }.takeIf { it.isNotEmpty() }?.average()?.toLong()
}

data class WakeStats(
    val currentStreak: Int,
    val bestStreak: Int,
    val totalWakeUps: Int,
    val successfulWakeUps: Int,
    val averageDismissMs: Long?,
    val averageSnoozes: Float,
    val checksPassed: Int,
    val checksFailed: Int,
    val days: List<DaySummary>,
) {
    val successRate: Float get() = if (totalWakeUps == 0) 0f else successfulWakeUps.toFloat() / totalWakeUps
}

/**
 * What really happened during a set of rings, counted from the history the phone keeps. Nothing here is sent anywhere.
 * Only finished rings count; one still ringing is not over yet.
 */
data class HowItWent(
    val rings: Int = 0,
    val backupMissions: Int = 0,
    val wayOuts: Int = 0,
    val missed: Int = 0,
    val pauses: Int = 0,
    val resumes: Int = 0,
    val pullBacks: Int = 0,
    val mutes: Int = 0,
) {
    /** Up with nothing to report: no backup mission, no way out, nothing missed, no pause, restart, pull-back or mute. */
    val straightUp: Boolean
        get() = rings > 0 && backupMissions == 0 && wayOuts == 0 && missed == 0 && pauses == 0 && resumes == 0 && pullBacks == 0 && mutes == 0

    companion object {
        fun of(events: List<AlarmEvent>): HowItWent {
            val finished = events.filter { it.outcome != AlarmOutcome.RINGING }
            return HowItWent(
                rings = finished.size,
                backupMissions = finished.count { it.fallbackUsed },
                wayOuts = finished.count { it.outcome == AlarmOutcome.EMERGENCY_STOPPED },
                missed = finished.count { it.outcome == AlarmOutcome.MISSED },
                pauses = finished.sumOf { it.pauses.coerceAtLeast(0) },
                resumes = finished.sumOf { it.resumes.coerceAtLeast(0) },
                pullBacks = finished.sumOf { it.pullBacks.coerceAtLeast(0) },
                mutes = finished.sumOf { it.mutesUsed.coerceAtLeast(0) },
            )
        }
    }
}

object WakeStatsCalculator {

    /**
     * Days without any alarm are neutral: they neither extend nor break a streak. A day with a failed wake-up
     * (missed alarm, emergency stop, failed "still awake?" check) breaks it.
     */
    fun compute(events: List<AlarmEvent>, zone: ZoneId): WakeStats {
        val finished = events.filter { it.outcome != AlarmOutcome.RINGING }
        val days = finished
            .groupBy { Instant.ofEpochMilli(it.firedAt).atZone(zone).toLocalDate() }
            .map { (date, list) -> DaySummary(date, list.sortedBy { it.firedAt }) }
            .sortedBy { it.date }

        var best = 0
        var run = 0
        for (day in days) {
            if (day.successful) {
                run++
                best = maxOf(best, run)
            } else {
                run = 0
            }
        }
        var current = 0
        for (day in days.asReversed()) {
            if (day.successful) current++ else break
        }

        val dismissed = finished.filter { it.outcome == AlarmOutcome.DISMISSED }
        return WakeStats(
            currentStreak = current,
            bestStreak = best,
            totalWakeUps = finished.size,
            successfulWakeUps = finished.count { it.isSuccessfulWakeUp },
            averageDismissMs = dismissed.mapNotNull { it.dismissDurationMs }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            averageSnoozes = if (finished.isEmpty()) 0f else finished.sumOf { it.snoozeCount }.toFloat() / finished.size,
            checksPassed = finished.count { it.wakeCheck == WakeCheckStatus.PASSED },
            checksFailed = finished.count { it.wakeCheck == WakeCheckStatus.FAILED },
            days = days,
        )
    }
}
