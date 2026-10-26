package com.riyadm.wakenudge.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.FilledButton
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.scheduling.NextAlarm

/**
 * About 2x1: the next alarm's time, day and label, an honest status line when something is skipped or paused, and a
 * small button to skip (or, for a one-time alarm, switch off) that alarm. Layouts per size are chosen with
 * [WidgetBreakpoints]; the responsive size set contains every breakpoint so each layout is really reachable.
 */
class NextAlarmWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 40.dp), DpSize(110.dp, 64.dp), DpSize(110.dp, 100.dp),
            DpSize(190.dp, 40.dp), DpSize(190.dp, 64.dp), DpSize(190.dp, 100.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val source = WidgetModelSource(context.surfaceEntry(), needsOutlook = true, needsAlarms = false)
        val initial = source.load()
        provideContent {
            val model by remember { source.updates() }.collectAsState(initial)
            WidgetTheme(model.settings.dynamicColor) { NextAlarmContent(model) }
        }
    }
}

class NextAlarmWidgetReceiver : SurfaceWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextAlarmWidget()
}

@Composable
private fun NextAlarmContent(model: WidgetModel) {
    val context = LocalContext.current
    val size = LocalSize.current
    val outlook = model.outlook ?: return
    val formatter = remember(model.settings.timeFormat) { SurfaceText(context, model.settings.timeFormat) }
    val texts = formatter.describe(outlook, model.today)
    val next = outlook.next

    val wide = size.width >= WidgetBreakpoints.WIDE
    val mid = size.height >= WidgetBreakpoints.MID
    val tall = size.height >= WidgetBreakpoints.TALL
    val skipInline = next != null && wide && mid && !tall
    val skipBelow = next != null && tall
    // While the ring just before the shown one is skipped, the button takes that back instead of skipping another.
    val undoSkipOf = (outlook.note as? OutlookNote.SkippingOnce)?.alarmId?.takeIf { it != 0L }

    WidgetSurface(GlanceModifier.clickable(openAlarmsAction(context))) {
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    if (texts.time == null) {
                        Text(
                            text = texts.note.orEmpty(),
                            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                            maxLines = 2,
                        )
                    } else {
                        Text(
                            text = texts.time,
                            style = TextStyle(
                                color = GlanceTheme.colors.onSurface,
                                fontSize = if (tall) 34.sp else if (mid) 28.sp else 24.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                        // With little height the status line replaces the day: it is the more important fact.
                        val line = if (!mid && texts.note != null) texts.note else texts.dayAndLabel(context)
                        if (line != null) {
                            Text(
                                text = line,
                                style = TextStyle(
                                    color = if (!mid && texts.note != null) GlanceTheme.colors.primary else GlanceTheme.colors.onSurfaceVariant,
                                    fontSize = 13.sp,
                                ),
                                maxLines = 1,
                            )
                        }
                        if (mid && texts.note != null) {
                            Text(
                                text = texts.note,
                                style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                                maxLines = 1,
                            )
                        }
                    }
                }
                if (skipInline) SkipOrUndoButton(next, undoSkipOf, GlanceModifier)
            }
            if (skipBelow) {
                Spacer(modifier = GlanceModifier.height(8.dp))
                SkipOrUndoButton(next, undoSkipOf, GlanceModifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SkipOrUndoButton(next: NextAlarm?, undoSkipOf: Long?, modifier: GlanceModifier) {
    when {
        undoSkipOf != null -> WidgetButton(
            LocalContext.current.getString(R.string.widget_action_undo_skip),
            actionRunCallback<UndoSkipCallback>(actionParametersOf(AlarmIdKey to undoSkipOf)),
            modifier,
        )
        next != null -> WidgetButton(
            LocalContext.current.getString(if (next.alarm.isRepeating) R.string.widget_action_skip_next else R.string.widget_action_turn_off),
            actionRunCallback<SkipNextCallback>(actionParametersOf(AlarmIdKey to next.alarm.id, OccurrenceKey to next.at.toInstant().toEpochMilli())),
            modifier,
        )
    }
}

@Composable
private fun WidgetButton(text: String, onClick: Action, modifier: GlanceModifier) {
    FilledButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = GlanceTheme.colors.secondaryContainer,
            contentColor = GlanceTheme.colors.onSecondaryContainer,
        ),
        maxLines = 1,
    )
}
