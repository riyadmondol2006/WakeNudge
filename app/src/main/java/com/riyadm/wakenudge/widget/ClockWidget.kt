package com.riyadm.wakenudge.widget

import android.content.Context
import android.text.format.DateFormat
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.TimeFormat
import java.util.Locale

/**
 * About 4x2: a big live clock and date, then the next alarm. The digits are `TextClock` views inside a RemoteViews
 * layout, so the launcher keeps them ticking without the widget being redrawn; only the alarm line is Glance.
 */
class ClockWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp), DpSize(180.dp, 150.dp),
            DpSize(250.dp, 110.dp), DpSize(250.dp, 150.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val source = WidgetModelSource(context.surfaceEntry(), needsOutlook = true, needsAlarms = false)
        val initial = source.load()
        provideContent {
            val model by remember { source.updates() }.collectAsState(initial)
            WidgetTheme(model.settings.dynamicColor) { ClockContent(model) }
        }
    }
}

class ClockWidgetReceiver : SurfaceWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ClockWidget()
}

private val ROOMY = 150.dp

@Composable
private fun ClockContent(model: WidgetModel) {
    val context = LocalContext.current
    val size = LocalSize.current
    val outlook = model.outlook ?: return
    val formatter = remember(model.settings.timeFormat) { SurfaceText(context, model.settings.timeFormat) }
    val texts = formatter.describe(outlook, model.today)
    val roomy = size.height >= ROOMY

    WidgetSurface(GlanceModifier.clickable(openAlarmsAction(context))) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AndroidRemoteViews(
                remoteViews = clockFace(context, model.settings.timeFormat, roomy),
                modifier = GlanceModifier.fillMaxWidth(),
            )
            Spacer(modifier = GlanceModifier.height(if (roomy) 12.dp else 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    provider = ImageProvider(R.drawable.ic_stat_alarm),
                    contentDescription = context.getString(R.string.widget_alarm_icon_description),
                    modifier = GlanceModifier.size(18.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Text(
                    text = alarmLine(context, texts),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1,
                )
            }
            if (texts.time != null && texts.note != null) {
                Text(
                    text = texts.note,
                    style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp),
                    maxLines = 1,
                    modifier = GlanceModifier.padding(start = 24.dp),
                )
            }
        }
    }
}

/** "7:30 AM · Tomorrow · Wake up", or the reason there is nothing to show. */
private fun alarmLine(context: Context, texts: NextAlarmTexts): String {
    val time = texts.time ?: return texts.note.orEmpty()
    val day = texts.day.orEmpty()
    return if (texts.label != null) {
        context.getString(R.string.widget_line_time_day_label, time, day, texts.label)
    } else {
        context.getString(R.string.widget_line_time_day, time, day)
    }
}

private fun clockFace(context: Context, preference: TimeFormat, roomy: Boolean): RemoteViews {
    val formats = ClockFormats.of(preference)
    val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEMMMMd")
    return RemoteViews(context.packageName, R.layout.widget_clock).apply {
        setCharSequence(R.id.widget_clock_time, "setFormat12Hour", formats.time12)
        setCharSequence(R.id.widget_clock_time, "setFormat24Hour", formats.time24)
        setCharSequence(R.id.widget_clock_marker, "setFormat12Hour", formats.marker12)
        setCharSequence(R.id.widget_clock_marker, "setFormat24Hour", formats.marker24)
        setCharSequence(R.id.widget_clock_date, "setFormat12Hour", datePattern)
        setCharSequence(R.id.widget_clock_date, "setFormat24Hour", datePattern)
        setTextViewTextSize(R.id.widget_clock_time, TypedValue.COMPLEX_UNIT_SP, if (roomy) 64f else 48f)
        setTextViewTextSize(R.id.widget_clock_marker, TypedValue.COMPLEX_UNIT_SP, if (roomy) 20f else 16f)
        setTextViewTextSize(R.id.widget_clock_date, TypedValue.COMPLEX_UNIT_SP, if (roomy) 17f else 15f)
    }
}
