package com.riyadm.wakenudge.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.SwitchDefaults
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.appwidget.components.FilledButton
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm

/** About 4x3, scrollable: each alarm with its time, label, repeat summary and an on/off switch. */
class AlarmListWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val source = WidgetModelSource(context.surfaceEntry(), needsOutlook = false, needsAlarms = true)
        val initial = source.load()
        provideContent {
            val model by remember { source.updates() }.collectAsState(initial)
            WidgetTheme(model.settings.dynamicColor) { AlarmListContent(model) }
        }
    }
}

class AlarmListWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AlarmListWidget()
}

/** Rows drawn before the list points the user to the app; keeps the RemoteViews adapter small. */
internal const val MAX_LIST_ALARMS = 8

@Composable
private fun AlarmListContent(model: WidgetModel) {
    val context = LocalContext.current
    val formatter = remember(model.settings.timeFormat) { SurfaceText(context, model.settings.timeFormat) }
    val shown = model.alarms.take(MAX_LIST_ALARMS)
    val hidden = model.alarms.size - shown.size

    WidgetSurface {
        Column(modifier = GlanceModifier.fillMaxSize().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 4.dp)) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().clickable(openAlarmsAction(context)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_stat_alarm),
                    contentDescription = context.getString(R.string.widget_alarm_icon_description),
                    modifier = GlanceModifier.size(20.dp),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                )
                Spacer(modifier = GlanceModifier.width(8.dp))
                Text(
                    text = context.getString(R.string.widget_list_title),
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                CircleIconButton(
                    imageProvider = ImageProvider(R.drawable.ic_widget_add),
                    contentDescription = context.getString(R.string.widget_add_icon_description),
                    onClick = addAlarmAction(context),
                    backgroundColor = GlanceTheme.colors.primaryContainer,
                    contentColor = GlanceTheme.colors.onPrimaryContainer,
                )
            }
            if (shown.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(shown, itemId = { it.id }) { alarm -> AlarmRow(alarm, model, formatter) }
                    if (hidden > 0) {
                        item {
                            Text(
                                text = context.resources.getQuantityString(R.plurals.widget_list_more, hidden, hidden),
                                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 8.dp).clickable(openAlarmsAction(context)),
                                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = context.getString(R.string.widget_list_empty),
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 15.sp),
        )
        Spacer(modifier = GlanceModifier.height(10.dp))
        FilledButton(
            text = context.getString(R.string.widget_action_add_alarm),
            onClick = addAlarmAction(context),
        )
    }
}

@Composable
private fun AlarmRow(alarm: Alarm, model: WidgetModel, formatter: SurfaceText) {
    val context = LocalContext.current
    val groupOff = alarm.groupId in model.groupsOff
    val live = alarm.enabled && !groupOff
    val skipping = alarm.skipEpochDay?.let { it >= model.today.toEpochDay() } == true

    val repeat = formatter.repeat(RepeatSummary.of(alarm, model.firstDay))
    val base = if (alarm.label.isBlank()) repeat else context.getString(R.string.widget_item_label_separator, alarm.label, repeat)
    val subtitle = when {
        groupOff -> context.getString(R.string.widget_item_group_off, base)
        skipping && alarm.enabled -> context.getString(R.string.widget_item_skipping_next, base)
        else -> base
    }

    val time = formatter.time(alarm.time)
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp).clickable(openAlarmsAction(context, alarm.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = time,
                style = TextStyle(
                    color = if (live) GlanceTheme.colors.onSurface else GlanceTheme.colors.onSurfaceVariant,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                maxLines = 1,
            )
        }
        Switch(
            checked = alarm.enabled,
            modifier = GlanceModifier.semantics { contentDescription = context.getString(R.string.widget_switch_description, time) },
            onCheckedChange = actionRunCallback<ToggleAlarmCallback>(actionParametersOf(AlarmIdKey to alarm.id)),
            colors = SwitchDefaults.colors(
                checkedThumbColor = GlanceTheme.colors.onPrimary,
                checkedTrackColor = GlanceTheme.colors.primary,
                uncheckedThumbColor = GlanceTheme.colors.outline,
                uncheckedTrackColor = GlanceTheme.colors.surfaceVariant,
            ),
        )
    }
}
