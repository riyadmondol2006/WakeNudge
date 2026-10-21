package com.riyadm.wakenudge.ui.alarms

import com.riyadm.wakenudge.ui.util.currentLocale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessAlarm
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.ui.components.AnimatedClockText
import com.riyadm.wakenudge.ui.components.ExpressiveSwitch
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.pressScale
import com.riyadm.wakenudge.ui.theme.rememberInteractionSource
import com.riyadm.wakenudge.ui.util.Formatters
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import java.time.ZonedDateTime

@Composable
fun AlarmCard(
    item: AlarmItem,
    settings: AppSettings,
    now: ZonedDateTime,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSkipNext: () -> Unit,
    onClearSkip: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val alarm = item.alarm
    val is24 = rememberIs24Hour(settings.timeFormat)
    val on = item.effectivelyEnabled
    val (digits, suffix) = TimeText.parts(alarm.time, is24)
    val scheme = MaterialTheme.colorScheme
    val contentAlpha = if (on) 1f else 0.62f
    val interaction = rememberInteractionSource()
    val firstDay = settings.firstDayOfWeek(java.time.temporal.WeekFields.of(currentLocale()).firstDayOfWeek)
    val repeat = Formatters.repeatSummary(context, alarm, firstDay)
    val status = statusLine(item, now)
    val summaryOnOff = stringResource(if (on) R.string.state_on else R.string.state_off)
    val spoken = stringResource(R.string.cd_alarm_summary, TimeText.format(alarm.time, is24), repeat, summaryOnOff)
    val editLabel = stringResource(R.string.cd_edit_alarm)

    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = RoundedCornerShape(32.dp),
        color = if (on) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interaction, 0.97f)
            .semantics(mergeDescendants = false) {
                contentDescription = spoken
                stateDescription = summaryOnOff
                onClick(label = editLabel) { onClick(); true }
            },
    ) {
        Column(Modifier.padding(start = 24.dp, end = 12.dp, top = 18.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        AnimatedClockText(
                            text = digits, style = ClockType.Card,
                            color = scheme.onSurface.copy(alpha = contentAlpha), spokenText = digits,
                        )
                        if (suffix.isNotEmpty()) {
                            Text(
                                suffix, style = ClockType.CardSuffix, color = scheme.onSurfaceVariant.copy(alpha = contentAlpha),
                                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
                            )
                        }
                    }
                    val title = alarm.label.ifBlank { null }
                    Text(
                        text = if (title != null) "$title · $repeat" else repeat,
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = contentAlpha),
                        maxLines = 1,
                    )
                }
                Box(Modifier.padding(end = 8.dp)) {
                    ExpressiveSwitch(
                        checked = alarm.enabled && on,
                        onCheckedChange = if (item.lockedMinutes != null && alarm.enabled) null else onToggle,
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    status.icon?.let {
                        Icon(it, null, tint = status.tint(scheme), modifier = Modifier.size(18.dp))
                    }
                    Text(status.text, style = MaterialTheme.typography.bodyMedium, color = status.tint(scheme), maxLines = 2)
                }
                CardMenu(item, onSkipNext, onClearSkip, onDuplicate, onDelete)
            }
            val extras = extraBadges(item)
            if (extras.isNotEmpty()) {
                Text(
                    extras, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            } else {
                Box(Modifier.size(2.dp))
            }
        }
    }
}

private class Status(val text: String, val icon: ImageVector?, val emphasis: Emphasis) {
    enum class Emphasis { NORMAL, PRIMARY, WARNING }

    fun tint(s: androidx.compose.material3.ColorScheme) = when (emphasis) {
        Emphasis.NORMAL -> s.onSurfaceVariant
        Emphasis.PRIMARY -> s.primary
        Emphasis.WARNING -> s.tertiary
    }
}

@Composable
private fun statusLine(item: AlarmItem, now: ZonedDateTime): Status {
    val context = LocalContext.current
    val alarm = item.alarm
    return when {
        item.lockedMinutes != null && alarm.enabled ->
            Status(stringResource(R.string.card_locked, item.lockedMinutes), Icons.Outlined.Lock, Status.Emphasis.WARNING)
        !alarm.enabled -> Status(stringResource(R.string.state_off), null, Status.Emphasis.NORMAL)
        item.group?.enabled == false -> Status(stringResource(R.string.card_group_off, item.group.name), Icons.Outlined.Task, Status.Emphasis.NORMAL)
        item.suppression is NextTriggerCalculator.Suppression.SkippedOnce ->
            Status(stringResource(R.string.card_skipping, Formatters.dateShort(item.suppression.date)), Icons.Outlined.SkipNext, Status.Emphasis.WARNING)
        item.suppression is NextTriggerCalculator.Suppression.Exception ->
            Status(stringResource(R.string.card_holiday_skip, Formatters.dateShort(item.suppression.date)), Icons.Outlined.Event, Status.Emphasis.WARNING)
        item.suppression is NextTriggerCalculator.Suppression.Vacation ->
            Status(stringResource(R.string.card_vacation), Icons.Outlined.BeachAccess, Status.Emphasis.WARNING)
        item.nextAt == null -> Status(stringResource(R.string.card_never_rings), null, Status.Emphasis.NORMAL)
        else -> {
            val until = Formatters.untilText(context, now.toInstant().toEpochMilli(), item.nextAt.toInstant().toEpochMilli())
            val day = Formatters.relativeDay(context, item.nextAt, now)
            Status("$until · $day", Icons.Outlined.AccessAlarm, Status.Emphasis.PRIMARY)
        }
    }
}

@Composable
private fun extraBadges(item: AlarmItem): String {
    val a = item.alarm
    val parts = buildList {
        if (a.missions.isNotEmpty()) add(pluralStringResource(R.plurals.badge_missions, a.missions.size, a.missions.size))
        if (a.wakeCheck.enabled) add(stringResource(R.string.badge_wake_check))
        if (a.volume.extraLoud) add(stringResource(R.string.badge_extra_loud))
        if (a.sunrise.enabled) add(stringResource(R.string.badge_sunrise))
        item.group?.let { add(it.name) }
    }
    return parts.joinToString("  ·  ")
}

@Composable
private fun CardMenu(item: AlarmItem, onSkipNext: () -> Unit, onClearSkip: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.cd_more_options)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val skipped = item.alarm.skipEpochDay != null
            if (item.alarm.enabled) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (skipped) R.string.menu_clear_skip else R.string.menu_skip_next)) },
                    leadingIcon = { Icon(if (skipped) Icons.Outlined.Undo else Icons.Outlined.SkipNext, null) },
                    onClick = { open = false; if (skipped) onClearSkip() else onSkipNext() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_duplicate)) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { open = false; onDuplicate() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

