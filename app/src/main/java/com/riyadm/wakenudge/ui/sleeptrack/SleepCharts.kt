package com.riyadm.wakenudge.ui.sleeptrack

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.sleep.ClipPolicy
import com.riyadm.wakenudge.domain.sleep.Epoch
import com.riyadm.wakenudge.domain.sleep.Night
import com.riyadm.wakenudge.domain.sleep.NightSummary
import com.riyadm.wakenudge.domain.sleep.SleepLimits
import com.riyadm.wakenudge.domain.sleep.SleepStage
import com.riyadm.wakenudge.ui.util.currentLocale
import com.riyadm.wakenudge.ui.util.spokenDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import kotlin.math.ceil

/** Stage colours from the theme, so dark, light, AMOLED and wallpaper colours all work. Never the only cue: see the legend. */
@Composable
@ReadOnlyComposable
fun stageColor(stage: SleepStage): Color {
    val scheme = MaterialTheme.colorScheme
    return when (stage) {
        SleepStage.DEEP -> scheme.primary
        SleepStage.LIGHT -> scheme.secondary
        SleepStage.REM -> scheme.tertiary
        SleepStage.AWAKE -> scheme.outline
    }
}

fun stageLabel(stage: SleepStage): Int = when (stage) {
    SleepStage.DEEP -> R.string.sleeptrack_stage_deep
    SleepStage.LIGHT -> R.string.sleeptrack_stage_light
    SleepStage.REM -> R.string.sleeptrack_stage_rem
    SleepStage.AWAKE -> R.string.sleeptrack_stage_awake
}

fun loudnessLabel(loudness: ClipPolicy.Loudness): Int = when (loudness) {
    ClipPolicy.Loudness.SOFT -> R.string.sleeptrack_loudness_soft
    ClipPolicy.Loudness.LOUD -> R.string.sleeptrack_loudness_loud
    ClipPolicy.Loudness.VERY_LOUD -> R.string.sleeptrack_loudness_very_loud
}

fun stageMs(s: NightSummary, stage: SleepStage): Long = when (stage) {
    SleepStage.DEEP -> s.deepMs
    SleepStage.LIGHT -> s.lightMs
    SleepStage.REM -> s.remMs
    SleepStage.AWAKE -> s.awakeMs
}

/** "Deep 1 h 20 min, Light 3 h 05 min, …" for a screen reader. */
fun stagesSpoken(res: Resources, s: NightSummary): String =
    SleepStage.entries.joinToString(", ") { res.getString(R.string.sleeptrack_stage_value, res.getString(stageLabel(it)), spokenDuration(res, stageMs(s, it))) }

fun timeOf(ms: Long, zone: ZoneId, is24: Boolean): String = TimeText.format(Instant.ofEpochMilli(ms).atZone(zone).toLocalTime(), is24)

@Composable
fun StageLegend(modifier: Modifier = Modifier, summary: NightSummary? = null) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // Top of the bar first, as it reads.
        listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP).forEach { stage ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(stageColor(stage), RoundedCornerShape(3.dp)))
                val label = stringResource(stageLabel(stage))
                Text(
                    if (summary == null) label else stringResource(R.string.sleeptrack_stage_value, label, TimeText.duration(stageMs(summary, stage))),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

/**
 * One stacked bar per night of the week on an hours axis: Deep at the bottom, then Light, REM and Awake. A night that was
 * tracked without enough data is an empty outline of its length, never a guess.
 */
@Composable
fun WeekStageBars(days: List<LocalDate>, main: Map<LocalDate, Night>, today: LocalDate, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val res = LocalResources.current
    val locale = currentLocale()
    val longest = main.values.maxOfOrNull { it.endedAt - it.startedAt } ?: 0L
    val hours = (ceil(longest / 3_600_000.0).toInt().coerceAtLeast(8).let { it + it % 2 }).coerceAtMost(16)
    val maxMs = hours * 3_600_000L
    val summary = days.joinToString("; ") { d ->
        val night = main[d]
        val value = when {
            night == null -> res.getString(R.string.sleeptrack_chart_no_night)
            !night.summary.hasEstimate -> res.getString(R.string.sleeptrack_chart_no_estimate)
            else -> spokenDuration(res, night.summary.asleepMs) + ". " + stagesSpoken(res, night.summary)
        }
        res.getString(R.string.sleeptrack_chart_day, d.dayOfWeek.getDisplayName(TextStyle.FULL, locale), value)
    }
    val colors = SleepStage.entries.associateWith { stageColor(it) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            Column(Modifier.fillMaxHeight().widthIn(min = 32.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.SpaceBetween) {
                listOf(hours, hours / 2, 0).forEach {
                    Text(stringResource(R.string.sleeptrack_chart_hours, it), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            }
            val grid = scheme.outlineVariant
            val outline = scheme.outline
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(start = 8.dp, top = 8.dp, bottom = 8.dp)) {
                listOf(0f, 0.5f, 1f).forEach { f ->
                    val y = size.height * f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
                val slot = size.width / days.size
                val barWidth = (slot * 0.55f).coerceAtMost(28.dp.toPx())
                days.forEachIndexed { i, d ->
                    val night = main[d] ?: return@forEachIndexed
                    val column = if (rtl) days.size - 1 - i else i
                    val x = slot * column + (slot - barWidth) / 2
                    val corner = CornerRadius(barWidth / 3)
                    if (!night.summary.hasEstimate) {
                        val h = (size.height * (night.endedAt - night.startedAt).toFloat() / maxMs).coerceIn(barWidth / 2, size.height)
                        drawRoundRect(outline, Offset(x, size.height - h), Size(barWidth, h), corner, style = Stroke(1.5.dp.toPx()))
                        return@forEachIndexed
                    }
                    val total = SleepStage.entries.sumOf { stageMs(night.summary, it) }.coerceAtMost(maxMs)
                    val barHeight = size.height * total.toFloat() / maxMs
                    val clip = Path().apply { addRoundRect(RoundRect(x, size.height - barHeight, x + barWidth, size.height, corner)) }
                    clipPath(clip) {
                        var top = size.height
                        SleepStage.entries.forEach { stage ->
                            val h = size.height * stageMs(night.summary, stage).toFloat() / maxMs
                            if (h <= 0f) return@forEach
                            drawRect(colors.getValue(stage), Offset(x, top - h), Size(barWidth, h))
                            top -= h
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 40.dp).clearAndSetSemantics {}) {
            days.forEach { d ->
                val isToday = d == today
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        d.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
                        color = if (isToday) scheme.primary else scheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp).then(if (isToday) Modifier.border(1.5.dp, scheme.primary, CircleShape) else Modifier).padding(top = 5.dp),
                    )
                }
            }
        }
    }
}

/** The night as a staircase of stages: Awake on top, Deep at the bottom, time running across. */
@Composable
fun StageTimeline(summary: NightSummary, startedAt: Long, endedAt: Long, zone: ZoneId, is24: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val res = LocalResources.current
    val lanes = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
    val colors = SleepStage.entries.associateWith { stageColor(it) }
    val start = timeOf(startedAt, zone, is24)
    val end = timeOf(endedAt, zone, is24)
    val description = res.getString(R.string.sleeptrack_timeline_desc, start, end, stagesSpoken(res, summary))
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(Modifier.fillMaxWidth().height(148.dp).clearAndSetSemantics {}) {
            Column(Modifier.fillMaxHeight().width(56.dp)) {
                lanes.forEach { lane ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Text(stringResource(stageLabel(lane)), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            val grid = scheme.outlineVariant
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                val laneH = size.height / lanes.size
                lanes.indices.forEach { i ->
                    val y = laneH * i + laneH / 2
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
                }
                val n = summary.stages.size.coerceAtLeast(1)
                val w = size.width / n
                var i = 0
                while (i < summary.stages.size) {
                    val stage = summary.stages[i]
                    var j = i
                    while (j + 1 < summary.stages.size && summary.stages[j + 1] == stage) j++
                    if (stage != null) {
                        val lane = lanes.indexOf(stage)
                        val left = if (rtl) size.width - (j + 1) * w else i * w
                        drawRoundRect(
                            colors.getValue(stage), Offset(left, laneH * lane + laneH * 0.15f), Size((j - i + 1) * w, laneH * 0.7f),
                            CornerRadius(3.dp.toPx()),
                        )
                    }
                    i = j + 1
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 56.dp, top = 4.dp).clearAndSetSemantics {}) {
            Text(start, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(end, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
    }
}

/** Sound level per epoch, with snore-like moments marked by dots along the top. */
@Composable
fun SoundLevelGraph(epochs: List<Epoch>, totalEpochs: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val levels = epochs.mapNotNull { it.soundDb }
    if (levels.isEmpty()) return
    val snoreEpochs = epochs.filter { it.snores > 0 }
    val snores = snoreEpochs.sumOf { it.snores }
    val description = pluralStringResource(R.plurals.sleeptrack_sound_desc, snores, snores)
    val line = scheme.primary
    val mark = scheme.tertiary
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(modifier.fillMaxWidth().height(96.dp).semantics { contentDescription = description }) {
        val lo = levels.min()
        val hi = levels.max().coerceAtLeast(lo + 10f)
        val n = maxOf(totalEpochs, (epochs.maxOfOrNull { it.index } ?: 0) + 1).coerceAtLeast(1)
        val w = size.width / n
        fun x(index: Int) = if (rtl) size.width - (index + 0.5f) * w else (index + 0.5f) * w
        val top = 12.dp.toPx()
        val path = Path()
        var open = false
        var last = -2
        epochs.sortedBy { it.index }.forEach { e ->
            val db = e.soundDb
            if (db == null) { open = false; return@forEach }
            val y = top + (size.height - top) * (1f - (db - lo) / (hi - lo))
            if (!open || e.index != last + 1) path.moveTo(x(e.index), y) else path.lineTo(x(e.index), y)
            open = true
            last = e.index
        }
        drawPath(path, line, style = Stroke(1.5.dp.toPx()))
        snoreEpochs.forEach { drawCircle(mark, radius = 3.dp.toPx(), center = Offset(x(it.index), top / 2)) }
    }
}

/** A clip's envelope as bars. */
@Composable
fun ClipWaveform(levels: List<Int>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.sleeptrack_waveform_desc)
    Canvas(modifier.height(36.dp).semantics { contentDescription = description }) {
        if (levels.isEmpty()) return@Canvas
        val slot = size.width / levels.size
        val bar = slot * 0.6f
        levels.forEachIndexed { i, level ->
            val h = (size.height * level / 100f).coerceAtLeast(2.dp.toPx())
            drawRoundRect(color, Offset(i * slot + (slot - bar) / 2, (size.height - h) / 2), Size(bar, h), CornerRadius(bar / 2))
        }
    }
}

/** Epochs a night spans, for drawing graphs to the same width as the timeline. */
fun epochCount(night: Night): Int = ceil((night.endedAt - night.startedAt) / SleepLimits.EPOCH_MS.toDouble()).toInt()
