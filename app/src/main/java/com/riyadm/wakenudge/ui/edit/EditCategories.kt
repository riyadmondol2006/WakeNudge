package com.riyadm.wakenudge.ui.edit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.engine.audio.AlarmVibrator
import com.riyadm.wakenudge.domain.model.AntiOversleepSettings
import com.riyadm.wakenudge.ui.util.LockText
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.components.ExpressiveSwitch
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.PolygonShape
import com.riyadm.wakenudge.ui.theme.WakeMotion
import com.riyadm.wakenudge.ui.util.fadeLabel
import com.riyadm.wakenudge.ui.util.minutesLabel

/**
 * The groups of "More options", in the order a person thinks about them: how it sounds, snoozing, how it ends, what
 * happens if you try to get out of it, voice and light, the check after you're up, and when the alarm applies.
 */
enum class AlarmCategory(val icon: ImageVector) {
    VOLUME(Icons.Outlined.VolumeUp),
    SNOOZE(Icons.Outlined.Snooze),
    STOPPING(Icons.Outlined.TaskAlt),
    LEAVING(Icons.Outlined.Shield),
    LIGHT(Icons.Outlined.LightMode),
    STILL_UP(Icons.Outlined.NotificationsActive),
    RULES(Icons.Outlined.EventRepeat),
}

/** Which categories are open. Survives rotation and process death so editing is never interrupted by a collapse. */
class ExpandedCategories(initial: Set<AlarmCategory> = emptySet()) {
    private val open = mutableStateOf(initial)

    fun isOpen(category: AlarmCategory) = category in open.value

    fun toggle(category: AlarmCategory) {
        open.value = if (category in open.value) open.value - category else open.value + category
    }

    companion object {
        val Saver: Saver<ExpandedCategories, List<String>> = Saver(
            save = { state -> state.open.value.map { it.name } },
            restore = { names -> ExpandedCategories(names.mapNotNull { n -> AlarmCategory.entries.firstOrNull { it.name == n } }.toSet()) },
        )
    }
}

@Composable
fun rememberExpandedCategories(): ExpandedCategories = rememberSaveable(saver = ExpandedCategories.Saver) { ExpandedCategories() }

/** What the editor passes down to the option groups besides the alarm. */
class CategoryOptions(
    val missionSlots: MissionSlots? = null,
    /** The way-out removal and the link to the Protection setup. */
    val protection: ProtectionLink? = null,
    val groups: List<com.riyadm.wakenudge.domain.model.AlarmGroup> = emptyList(),
)

/** [rehearsed]: this alarm, with the missions it has now, had a finished rehearsal, which removing its way out needs. */
class ProtectionLink(val rehearsed: (Alarm) -> Boolean, val open: () -> Unit)

/** Emits the option groups as collapsible cards into a lazy list. */
fun LazyListScope.alarmCategories(
    alarm: Alarm,
    enabled: Boolean,
    expanded: ExpandedCategories,
    options: CategoryOptions,
    vibrator: AlarmVibrator,
    onChange: ((Alarm) -> Alarm) -> Unit,
) {
    AlarmCategory.entries.forEach { category ->
        item(key = "category-${category.name}") {
            val switchLabel = stringResource(R.string.cat_snooze_switch)
            CategoryCard(
                icon = category.icon,
                title = stringResource(categoryTitle(category)),
                summary = categorySummary(category, alarm),
                expanded = expanded.isOpen(category),
                onToggle = { expanded.toggle(category) },
                trailing = if (category == AlarmCategory.SNOOZE) {
                    {
                        // Snooze is the one option people flip on every alarm, so it is a switch right on the card.
                        ExpressiveSwitch(
                            checked = alarm.snooze.enabled,
                            onCheckedChange = { on -> onChange { it.copy(snooze = it.snooze.copy(enabled = on)) } },
                            enabled = enabled,
                            modifier = Modifier.semantics { contentDescription = switchLabel },
                        )
                    }
                } else null,
            ) {
                when (category) {
                    AlarmCategory.VOLUME -> {
                        VolumeSection(alarm, enabled, onChange)
                        VibrationSection(alarm, enabled, vibrator, onChange)
                    }
                    AlarmCategory.SNOOZE -> SnoozeSection(
                        alarm, enabled, onChange,
                        snoozeMissions = { options.missionSlots?.snoozeMissions?.invoke(alarm, enabled, onChange) },
                        showTitle = false,
                    )
                    AlarmCategory.STOPPING -> StoppingSection(alarm, enabled, onChange, options.protection)
                    AlarmCategory.LEAVING -> LeavingSection(alarm, enabled, onChange, options.protection)
                    AlarmCategory.LIGHT -> LightSection(alarm, enabled, onChange)
                    AlarmCategory.STILL_UP -> StillUpSection(alarm, enabled, onChange)
                    AlarmCategory.RULES -> ExtrasSection(alarm, enabled, onChange, options.groups)
                }
            }
        }
    }
}

@Composable
fun CategoryCard(
    icon: ImageVector,
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** A control that sits in the header, so it can be used without opening the card (for example the Snooze switch). */
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val haptics = LocalWakeHaptics.current
    val scheme = MaterialTheme.colorScheme
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, WakeMotion.defaultSpatial(), label = "categoryChevron")
    val stateText = stringResource(if (expanded) R.string.cat_state_expanded else R.string.cat_state_collapsed)
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).animateContentSize(WakeMotion.defaultSpatial()),
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // The icon and texts are one tappable, announced-as-a-unit area; a trailing control (the Snooze switch) and the
                // chevron are separate siblings, so a screen reader never mistakes the whole card for a switch.
                Row(
                    Modifier
                        .weight(1f)
                        .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.cat_collapse else R.string.cat_expand)) {
                            haptics.tick()
                            onToggle()
                        }
                        .semantics(mergeDescendants = true) { stateDescription = stateText }
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(PolygonShape(Blobs.Cookie)).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
                        Icon(icon, null, tint = scheme.onSecondaryContainer)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        if (!expanded) {
                            Text(
                                summary, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (trailing != null) {
                    trailing()
                    Spacer(Modifier.width(4.dp))
                }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.cat_collapse else R.string.cat_expand)) {
                            haptics.tick()
                            onToggle()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ExpandMore, null, tint = scheme.onSurfaceVariant, modifier = Modifier.rotate(chevron))
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(WakeMotion.defaultSpatial()) + fadeIn(WakeMotion.defaultEffects()),
                exit = shrinkVertically(WakeMotion.defaultSpatial()) + fadeOut(WakeMotion.fastEffects()),
            ) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp), content = content)
            }
        }
    }
}

private fun categoryTitle(c: AlarmCategory) = when (c) {
    AlarmCategory.VOLUME -> R.string.cat_volume
    AlarmCategory.SNOOZE -> R.string.cat_snooze
    AlarmCategory.STOPPING -> R.string.cat_stopping
    AlarmCategory.LEAVING -> R.string.cat_leaving
    AlarmCategory.LIGHT -> R.string.cat_light
    AlarmCategory.STILL_UP -> R.string.cat_still_up
    AlarmCategory.RULES -> R.string.cat_rules
}

/** One line that tells the current state of a collapsed category, so nothing needs opening just to check. */
@Composable
fun categorySummary(category: AlarmCategory, alarm: Alarm): String {
    val separator = stringResource(R.string.cat_separator)
    val res = androidx.compose.ui.platform.LocalResources.current
    val a = alarm.antiOversleep
    return when (category) {
        AlarmCategory.VOLUME -> {
            val v = alarm.volume
            val level = if (v.level == 0) stringResource(R.string.cat_system_volume) else "${v.level}%"
            val fade = if (v.rampSeconds > 0) stringResource(R.string.cat_fade_in, fadeLabel(v.rampSeconds)) else null
            val loud = if (v.extraLoudStrength > 0) stringResource(R.string.cat_extra_loud, v.extraLoudStrength) else null
            val floor = if (a.volumeFloorPercent > 0) stringResource(R.string.cat_floor, a.volumeFloorPercent) else null
            val vibration = if (alarm.vibration.enabled) stringResource(vibrationLabel(alarm.vibration.pattern)) else stringResource(R.string.cat_vibration_off)
            listOfNotNull(level, loud, floor, fade, vibration).joinToString(separator)
        }
        AlarmCategory.SNOOZE -> {
            val s = alarm.snooze
            if (!s.enabled) stringResource(R.string.state_off)
            else {
                val limit = if (s.maxCount == 0) stringResource(R.string.snooze_unlimited)
                else pluralStringResource(R.plurals.cat_snooze_limit, s.maxCount, s.maxCount)
                listOf(minutesLabel(s.minutes), limit).joinToString(separator)
            }
        }
        AlarmCategory.STOPPING -> {
            val ringing = if (a.ringTimeoutMinutes == AntiOversleepSettings.RING_TIMEOUT_NEVER) stringResource(R.string.cat_keeps_ringing)
            else stringResource(R.string.cat_stops_after, minutesLabel(a.ringTimeoutMinutes))
            val hold = if (a.holdToDismissSeconds > 0) stringResource(R.string.cat_hold, stringResource(R.string.unit_seconds, a.holdToDismissSeconds)) else null
            val wayOut = when {
                alarm.missions.isEmpty() -> null
                a.wayOutRemoved -> stringResource(R.string.cat_no_way_out)
                !a.emergencyStopEnabled -> stringResource(R.string.cat_no_emergency_stop)
                else -> null
            }
            val backup = if (a.backupEnabled) stringResource(R.string.cat_backup_in, minutesLabel(a.backupDelayMinutes)) else null
            listOfNotNull(ringing, wayOut, hold, backup).joinToString(separator)
        }
        AlarmCategory.LEAVING -> {
            val back = when {
                !a.returnToAlarm -> null
                a.forceReturn -> stringResource(R.string.cat_comes_back)
                else -> stringResource(R.string.cat_reminds)
            }
            val lock = if (a.editLockMinutes > 0) stringResource(R.string.cat_edit_lock, LockText.duration(res, a.editLockMinutes)) else null
            val restarts = pluralStringResource(R.plurals.cat_restarts, a.maxResumes, a.maxResumes)
            listOfNotNull(back, lock, restarts).joinToString(separator)
        }
        AlarmCategory.LIGHT -> {
            val voice = if (alarm.voice.enabled) stringResource(R.string.cat_announces) else null
            val sunrise = if (alarm.sunrise.enabled) stringResource(R.string.cat_sunrise) else null
            val bright = if (a.fullBrightness) stringResource(R.string.cat_bright) else null
            listOfNotNull(voice, sunrise, bright).ifEmpty { listOf(stringResource(R.string.state_off)) }.joinToString(separator)
        }
        AlarmCategory.STILL_UP -> {
            val w = alarm.wakeCheck
            if (!w.enabled) stringResource(R.string.state_off)
            else listOfNotNull(
                stringResource(R.string.cat_check_after, minutesLabel(w.delayMinutes)),
                if (w.requireProof) stringResource(R.string.cat_with_proof) else null,
            ).joinToString(separator)
        }
        AlarmCategory.RULES -> {
            val skips = if (alarm.isRepeating && alarm.respectExceptions) stringResource(R.string.cat_skips_holidays) else null
            val vacation = if (alarm.ignoreVacation) stringResource(R.string.cat_ignores_vacation) else null
            val notice = if (alarm.upcomingNoticeMinutes > 0) stringResource(R.string.cat_notice, minutesLabel(alarm.upcomingNoticeMinutes)) else null
            val delete = if (!alarm.isRepeating && alarm.deleteAfterUse) stringResource(R.string.cat_deletes) else null
            listOfNotNull(skips, vacation, notice, delete).ifEmpty { listOf(stringResource(R.string.cat_defaults)) }.joinToString(separator)
        }
    }
}
