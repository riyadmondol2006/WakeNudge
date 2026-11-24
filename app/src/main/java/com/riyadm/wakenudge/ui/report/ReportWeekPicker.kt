package com.riyadm.wakenudge.ui.report

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.util.currentLocale
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The week picker every Report section shares: "This week" or "Last week" over the dates, arrows to earlier weeks, and
 * never past this week. [weeksBack] is 0 for this week. [detail] is an optional line under the dates.
 */
@Composable
fun ReportWeekPicker(
    start: LocalDate,
    weeksBack: Long,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val locale = currentLocale()
    val format = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale)
    val range = stringResource(R.string.report_week_range, format.format(start), format.format(start.plusDays(6)))
    val title = when (weeksBack) {
        0L -> stringResource(R.string.report_week_this)
        1L -> stringResource(R.string.report_week_last)
        else -> null
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.report_week_previous)) }
        Column(
            Modifier.weight(1f).semantics(mergeDescendants = true) {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                range,
                style = if (title == null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        IconButton(onClick = onNext, enabled = canGoForward) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.report_week_next))
        }
    }
}
