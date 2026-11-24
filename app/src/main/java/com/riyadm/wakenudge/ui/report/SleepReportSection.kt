package com.riyadm.wakenudge.ui.report

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.TimeFormat
import com.riyadm.wakenudge.ui.components.HeroCard
import com.riyadm.wakenudge.ui.sleeptrack.ClipRow
import com.riyadm.wakenudge.ui.sleeptrack.NightDetailDialog
import com.riyadm.wakenudge.ui.sleeptrack.SectionCard
import com.riyadm.wakenudge.ui.sleeptrack.SleepReportViewModel
import com.riyadm.wakenudge.ui.sleeptrack.StageLegend
import com.riyadm.wakenudge.ui.sleeptrack.StatTile
import com.riyadm.wakenudge.ui.sleeptrack.WeekStageBars
import com.riyadm.wakenudge.ui.sleeptrack.nightTitle
import com.riyadm.wakenudge.ui.sleeptrack.rememberClipListener
import com.riyadm.wakenudge.ui.util.currentLocale
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields

/**
 * Sleep report: the week's nights as stacked stage bars, the week's averages, the loudest snore clip, and the way into a
 * single night. Stages are estimates and the section says so.
 */
@Composable
fun SleepReportSection(nav: ReportNav, modifier: Modifier = Modifier, viewModel: SleepReportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val playing by viewModel.clips.playing.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val current = settings
    LaunchedEffect(current?.weekStart, locale) {
        if (current != null) viewModel.setFirstDay(current.firstDayOfWeek(WeekFields.of(locale).firstDayOfWeek))
    }
    val is24 = rememberIs24Hour(current?.timeFormat ?: TimeFormat.SYSTEM)
    var nightOpen by rememberSaveable { mutableStateOf<Long?>(null) }
    val listen = rememberClipListener(viewModel.clips)

    if (!state.loaded) return
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!state.hasAny) {
            item {
                HeroCard(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Bedtime, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(stringResource(R.string.sleeptrack_empty_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.sleeptrack_empty_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Button(onClick = nav.openSleepTracking) { Text(stringResource(R.string.sleep_track_open)) }
                    }
                }
            }
            item { HowNote() }
            return@LazyColumn
        }
        item {
            // [weekStart] begins a week and today lies in this week, so whole weeks between them is how far back this is.
            ReportWeekPicker(state.weekStart, (state.today.toEpochDay() - state.weekStart.toEpochDay()) / 7, state.canGoForward, viewModel::previousWeek, viewModel::nextWeek)
        }
        item {
            val none = stringResource(R.string.sleeptrack_none_value)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(stringResource(R.string.sleeptrack_avg_asleep), state.averages.asleepMs?.let { TimeText.duration(it) } ?: none, Modifier.weight(1f))
                StatTile(stringResource(R.string.sleeptrack_avg_latency), state.averages.latencyMs?.let { TimeText.duration(it) } ?: none, Modifier.weight(1f))
            }
        }
        item {
            SectionCard(stringResource(R.string.sleeptrack_week_chart)) {
                WeekStageBars(state.days, state.main, state.today)
                StageLegend()
            }
        }
        state.loudest?.let { clip ->
            item {
                SectionCard(stringResource(R.string.sleeptrack_snore_highlight)) {
                    ClipRow(clip, playing == clip.id, state.zone, is24, onListen = { listen(clip) }, onShare = null, onDelete = null)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = nav.openSleepTracking, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.sleep_track_open), textAlign = TextAlign.Center) }
                val latest = state.nights.lastOrNull()
                OutlinedButton(onClick = { latest?.let { nightOpen = it.sessionId } }, enabled = latest != null, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.sleeptrack_view_night), textAlign = TextAlign.Center)
                }
            }
        }
        if (state.nights.isEmpty()) {
            item { Text(stringResource(R.string.sleeptrack_week_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
        } else {
            item { Text(stringResource(R.string.sleeptrack_nights_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp, top = 4.dp).semantics { heading() }) }
            items(state.nights.reversed(), key = { it.sessionId }) { night ->
                val none = stringResource(R.string.sleeptrack_none_value)
                Surface(
                    shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { nightOpen = night.sessionId },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(nightTitle(night, state.zone, is24), style = MaterialTheme.typography.titleSmall)
                        val asleep = if (night.summary.hasEstimate) TimeText.duration(night.summary.asleepMs) else none
                        Text(stringResource(R.string.sleeptrack_night_row, TimeText.duration(night.summary.inBedMs), asleep), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (night.short) Text(stringResource(R.string.sleeptrack_short), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        item { HowNote() }
    }
    nightOpen?.let { id -> NightDetailDialog(id, onDismiss = { nightOpen = null }) }
}

@Composable
private fun HowNote() {
    Text(
        stringResource(R.string.sleeptrack_how_short), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
    )
}

