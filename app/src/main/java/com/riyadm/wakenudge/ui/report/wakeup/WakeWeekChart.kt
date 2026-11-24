package com.riyadm.wakenudge.ui.report.wakeup

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.stats.WakeDayKind
import com.riyadm.wakenudge.domain.stats.WakeDayReport
import com.riyadm.wakenudge.domain.stats.WeekWakeReport
import com.riyadm.wakenudge.ui.util.currentLocale
import com.riyadm.wakenudge.ui.util.spokenDuration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle

private val CHART_HEIGHT = 176.dp
private val AXIS_WIDTH = 52.dp

/** One pill per day from its first ring to its dismissal, on an axis that fits this week. */
@Composable
fun WakeWeekChart(
    week: WeekWakeReport,
    today: LocalDate,
    is24: Boolean,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val scale = week.chartScale
    val locale = currentLocale()
    val resources = LocalResources.current
    val descriptions = week.days.map { day -> dayDescription(day, is24, resources) }
    Column(modifier) {
        if (scale.averageMinute != null) {
            Text(
                stringResource(R.string.wakereport_average_line),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.tertiary,
                modifier = Modifier.padding(start = AXIS_WIDTH, bottom = 2.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(AXIS_WIDTH).height(CHART_HEIGHT)) {
                scale.tickMinutes.forEach { tick ->
                    val fraction = scale.fractionOf(tick)
                    Text(
                        TimeText.format(tickTime(tick), is24, locale),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(y = (CHART_HEIGHT * fraction) - 8.dp)
                            .padding(end = 6.dp),
                    )
                }
            }
            Box(Modifier.weight(1f).height(CHART_HEIGHT)) {
                val primary = scheme.primary
                val error = scheme.error
                val tertiary = scheme.tertiary
                val track = scheme.surfaceContainerHighest
                val outline = scheme.outline
                Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                    val slot = size.width / week.days.size.coerceAtLeast(1)
                    val barWidth = slot * 0.46f
                    scale.tickMinutes.forEach { tick ->
                        val y = size.height * scale.fractionOf(tick)
                        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    scale.averageMinute?.let { average ->
                        val y = size.height * scale.fractionOf(average)
                        drawLine(
                            tertiary, Offset(0f, y), Offset(size.width, y), strokeWidth = 3f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                        )
                    }
                    week.days.forEachIndexed { index, day ->
                        val centreX = slot * index + slot / 2
                        val x = centreX - barWidth / 2
                        if (day.date == today) {
                            drawRoundRect(
                                scheme.secondaryContainer.copy(alpha = 0.55f),
                                Offset(slot * index + 2f, 0f), Size(slot - 4f, size.height),
                                CornerRadius(18f, 18f),
                            )
                        }
                        drawRoundRect(track, Offset(x, 0f), Size(barWidth, size.height), CornerRadius(barWidth / 2, barWidth / 2))
                        val wake = day.wakeUp
                        if (wake != null) {
                            val yTop = size.height * scale.fractionOf(minuteOfDay(wake.wakeUpTime, scale))
                            val yBottom = size.height * scale.fractionOf(minuteOfDay(wake.firstRingTime, scale))
                            val height = (yBottom - yTop).coerceAtLeast(barWidth)
                            drawRoundRect(primary, Offset(x, yBottom - height), Size(barWidth, height), CornerRadius(barWidth / 2, barWidth / 2))
                        } else if (day.kind == WakeDayKind.NO_WAKE_UP || day.kind == WakeDayKind.STILL_RINGING) {
                            day.firstRingTime?.let { first ->
                                val y = size.height * scale.fractionOf(minuteOfDay(first, scale))
                                val colour = if (day.kind == WakeDayKind.STILL_RINGING) primary else error
                                drawCircle(colour, radius = barWidth * 0.30f, center = Offset(centreX, y))
                                drawCircle(scheme.surface, radius = barWidth * 0.13f, center = Offset(centreX, y))
                            }
                        } else {
                            // Empty day: only the faint track, so a missing morning reads as missing.
                            drawLine(outline.copy(alpha = 0.5f), Offset(centreX, size.height * 0.46f), Offset(centreX, size.height * 0.54f), strokeWidth = 3f)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                    week.days.forEachIndexed { index, day ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { onDayClick(day.date) }
                                .semantics { contentDescription = descriptions[index] },
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Spacer(Modifier.width(AXIS_WIDTH))
            Row(Modifier.weight(1f)) {
                week.days.forEach { day ->
                    val isToday = day.date == today
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .then(if (isToday) Modifier.background(scheme.primary) else Modifier)
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isToday) scheme.onPrimary else scheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun dayDescription(day: WakeDayReport, is24: Boolean, resources: android.content.res.Resources): String {
    val locale = currentLocale()
    val dayName = day.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
    return when {
        day.wakeUp != null -> stringResource(
            R.string.wakereport_chart_day_wake, dayName,
            TimeText.format(day.wakeUp.wakeUpTime, is24, locale),
            spokenDuration(resources, day.wakeUp.timeToWakeMs),
        )
        day.kind == WakeDayKind.STILL_RINGING -> stringResource(R.string.wakereport_chart_day_ringing, dayName)
        day.kind == WakeDayKind.NO_WAKE_UP -> stringResource(R.string.wakereport_chart_day_no_wake, dayName)
        else -> stringResource(R.string.wakereport_chart_day_empty, dayName)
    }
}

private fun tickTime(tickMinute: Double): LocalTime {
    val minute = ((tickMinute.toLong() % 1440) + 1440) % 1440
    return LocalTime.of((minute / 60).toInt(), (minute % 60).toInt())
}

private fun minuteOfDay(time: LocalTime, scale: com.riyadm.wakenudge.domain.stats.WakeChartScale): Double {
    var minute = time.hour * 60.0 + time.minute + time.second / 60.0
    if (scale.startMinute >= 24 * 60 && minute < 12 * 60) minute += 24 * 60
    return minute
}
