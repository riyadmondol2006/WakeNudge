package com.riyadm.wakenudge.ui.report.wakeup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.AlarmEvent
import com.riyadm.wakenudge.domain.model.AlarmOutcome
import com.riyadm.wakenudge.domain.model.WakeCheckStatus
import com.riyadm.wakenudge.domain.stats.HowItWent
import com.riyadm.wakenudge.domain.stats.WakeDayReport
import com.riyadm.wakenudge.ui.util.currentLocale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One day in full: every ring, what it took, and the "How it went" facts for that day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyReportSheet(
    day: WakeDayReport,
    zone: ZoneId,
    is24: Boolean,
    onDismiss: () -> Unit,
) {
    val locale = currentLocale()
    val title = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(day.date)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.wakereport_daily_title, title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.wakereport_close)) }
            }
            val wake = day.wakeUp
            Text(
                if (wake != null) {
                    stringResource(
                        R.string.wakereport_daily_wake_summary,
                        TimeText.format(wake.wakeUpTime, is24, locale),
                        TimeText.duration(wake.timeToWakeMs),
                    )
                } else {
                    stringResource(R.string.wakereport_daily_no_wake_summary)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (day.events.isEmpty()) {
                Text(stringResource(R.string.wakereport_daily_no_rings), style = MaterialTheme.typography.bodyLarge)
            } else {
                day.events.forEach { event -> RingRow(event, zone, is24) }
            }
            if (day.events.isNotEmpty()) {
                DailyHowItWent(HowItWent.of(day.events))
            }
        }
    }
}

@Composable
private fun RingRow(event: AlarmEvent, zone: ZoneId, is24: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val locale = currentLocale()
    val fired = Instant.ofEpochMilli(event.firedAt).atZone(zone)
    val outcome = when (event.outcome) {
        AlarmOutcome.DISMISSED -> stringResource(R.string.outcome_dismissed)
        AlarmOutcome.EMERGENCY_STOPPED -> stringResource(R.string.outcome_emergency)
        AlarmOutcome.MISSED -> stringResource(R.string.outcome_missed)
        AlarmOutcome.RINGING -> stringResource(R.string.outcome_ringing)
    }
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    event.label.ifBlank { TimeText.format(fired.toLocalTime(), is24, locale) },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(outcome, style = MaterialTheme.typography.labelMedium, color = scheme.primary)
            }
            val lines = buildList {
                add(stringResource(R.string.wakereport_ring_first, TimeText.format(fired.toLocalTime(), is24, locale)))
                if (event.outcome == AlarmOutcome.RINGING) {
                    add(stringResource(R.string.wakereport_ring_still))
                } else if (event.outcome == AlarmOutcome.DISMISSED) {
                    event.dismissDurationMs?.let { add(stringResource(R.string.wakereport_ring_took, TimeText.duration(it))) }
                }
                if (event.snoozeCount > 0) add(pluralStringResource(R.plurals.morning_snoozes, event.snoozeCount, event.snoozeCount))
                if (event.missionsTotal > 0) {
                    add(stringResource(R.string.wakereport_ring_missions, event.missionsDone.coerceAtLeast(0), event.missionsTotal))
                } else if (event.outcome == AlarmOutcome.DISMISSED) {
                    add(stringResource(R.string.wakereport_ring_missions_none))
                }
                if (event.fallbackUsed) add(stringResource(R.string.wakereport_ring_backup_mission))
                if (event.backupFired) add(stringResource(R.string.wakereport_ring_backup_fired))
                if (event.outcome == AlarmOutcome.EMERGENCY_STOPPED) add(stringResource(R.string.wakereport_way_out))
                add(
                    stringResource(
                        when (event.wakeCheck) {
                            WakeCheckStatus.PASSED -> R.string.wakereport_check_passed
                            WakeCheckStatus.FAILED -> R.string.wakereport_check_failed
                            WakeCheckStatus.PENDING -> R.string.wakereport_check_pending
                            WakeCheckStatus.CANCELLED -> R.string.wakereport_check_cancelled
                            WakeCheckStatus.NONE -> R.string.wakereport_check_none
                        },
                    ),
                )
                if (event.pauses > 0) add(pluralStringResource(R.plurals.morning_badge_pauses, event.pauses, event.pauses))
                if (event.resumes > 0) add(pluralStringResource(R.plurals.morning_badge_resumes, event.resumes, event.resumes))
                if (event.pullBacks > 0) add(pluralStringResource(R.plurals.morning_badge_pull_backs, event.pullBacks, event.pullBacks))
                if (event.mutesUsed > 0) add(pluralStringResource(R.plurals.morning_badge_mutes, event.mutesUsed, event.mutesUsed))
            }
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun DailyHowItWent(how: HowItWent) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                stringResource(R.string.morning_how_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                pluralStringResource(R.plurals.morning_how_rings, how.rings, how.rings),
                style = MaterialTheme.typography.bodyMedium,
            )
            val lines = buildList {
                if (how.backupMissions > 0) add(pluralStringResource(R.plurals.morning_how_backup_mission, how.backupMissions, how.backupMissions))
                if (how.wayOuts > 0) add(pluralStringResource(R.plurals.morning_how_way_out, how.wayOuts, how.wayOuts))
                if (how.missed > 0) add(pluralStringResource(R.plurals.morning_how_missed, how.missed, how.missed))
                if (how.pauses > 0) add(pluralStringResource(R.plurals.morning_how_pauses, how.pauses, how.pauses))
                if (how.resumes > 0) add(pluralStringResource(R.plurals.morning_how_resumes, how.resumes, how.resumes))
                if (how.pullBacks > 0) add(pluralStringResource(R.plurals.morning_how_pull_backs, how.pullBacks, how.pullBacks))
                if (how.mutes > 0) add(pluralStringResource(R.plurals.morning_how_mutes, how.mutes, how.mutes))
            }
            if (how.straightUp || lines.isEmpty()) {
                if (how.rings > 0) Text(stringResource(R.string.morning_how_clean), style = MaterialTheme.typography.bodyMedium)
            } else {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
