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
import androidx.compose.material.icons.outlined.MusicNote
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
import com.riyadm.wakenudge.engine.audio.SoundPreviewer
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.components.ExpressiveSwitch
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.PolygonShape
import com.riyadm.wakenudge.ui.theme.WakeMotion
import com.riyadm.wakenudge.ui.util.fadeLabel
import com.riyadm.wakenudge.ui.util.minutesLabel

/**
 * The groups every alarm option belongs to, in the order a person thinks about them: what you must do to get up,
 * what you hear and feel, how snoozing works, the safety net, and the rules about *when* the alarm applies.
 * The alarm editor and the "new alarm defaults" screen both render them, so the two can never drift apart.
 */
enum class AlarmCategory(val icon: ImageVector) {
    WAKE_UP(Icons.Outlined.TaskAlt),
    SOUND(Icons.Outlined.MusicNote),
    VOLUME(Icons.Outlined.VolumeUp),
    SNOOZE(Icons.Outlined.Snooze),
    PROTECTION(Icons.Outlined.Shield),
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

/** What differs between the editor of a real alarm and the editor of the defaults for new ones. */
class CategoryOptions(
    /** Per-alarm features that have no meaning as a default: voice announcements, sunrise, missions, still-awake check, rules. */
    val perAlarm: Boolean,
    val missionSlots: MissionSlots? = null,
)

/** Emits the option categories as collapsible cards into a lazy list. */
fun LazyListScope.alarmCategories(
    alarm: Alarm,
    enabled: Boolean,
    expanded: ExpandedCategories,
    options: CategoryOptions,
    previewer: SoundPreviewer,
    vibrator: AlarmVibrator,
    onChange: ((Alarm) -> Alarm) -> Unit,
) {
    val shown = AlarmCategory.entries.filter { options.perAlarm || it != AlarmCategory.WAKE_UP && it != AlarmCategory.RULES }
    shown.forEach { category ->
        item(key = "category-${category.name}") {
            val switchLabel = stringResource(R.string.cat_snooze_switch)
            CategoryCard(
                icon = category.icon,
                title = stringResource(categoryTitle(category)),
                summary = categorySummary(category, alarm, options.perAlarm),
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
                    AlarmCategory.WAKE_UP -> {
                        options.missionSlots?.missions?.invoke(alarm, enabled, onChange)
                        WakeCheckSection(alarm, enabled, onChange)
                    }
                    AlarmCategory.SOUND -> {
                        SoundSection(alarm, enabled, previewer, onChange, showVoice = options.perAlarm)
                        if (options.perAlarm) SunriseSection(alarm, enabled, onChange)
                    }
                    AlarmCategory.VOLUME -> {
                        VolumeSection(alarm, enabled, onChange)
                        VibrationSection(alarm, enabled, vibrator, onChange)
                    }
                    AlarmCategory.SNOOZE -> SnoozeSection(
                        alarm, enabled, onChange,
                        snoozeMissions = { options.missionSlots?.snoozeMissions?.invoke(alarm, enabled, onChange) },
                        showTitle = false,
                    )
                    AlarmCategory.PROTECTION -> AntiOversleepSection(alarm, enabled, onChange, showTitle = false)
                    AlarmCategory.RULES -> ExtrasSection(alarm, enabled, onChange, showTitle = false)
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
    AlarmCategory.WAKE_UP -> R.string.cat_wake_up
    AlarmCategory.SOUND -> R.string.cat_sound
    AlarmCategory.VOLUME -> R.string.cat_volume
    AlarmCategory.SNOOZE -> R.string.cat_snooze
    AlarmCategory.PROTECTION -> R.string.cat_protection
    AlarmCategory.RULES -> R.string.cat_rules
}

/** One line that tells the current state of a collapsed category, so nothing needs opening just to check. */
@Composable
fun categorySummary(category: AlarmCategory, alarm: Alarm, perAlarm: Boolean): String {
    val separator = stringResource(R.string.cat_separator)
    return when (category) {
        AlarmCategory.WAKE_UP -> {
            val missions = if (alarm.missions.isEmpty()) stringResource(R.string.cat_no_missions)
            else pluralStringResource(R.plurals.cat_missions, alarm.missions.size, alarm.missions.size)
            val check = if (alarm.wakeCheck.enabled) stringResource(R.string.cat_still_awake) else null
            listOfNotNull(missions, check).joinToString(separator)
        }
        AlarmCategory.SOUND -> {
            val tone = rememberSoundTitle(alarm.sound)
            val voice = if (perAlarm && alarm.voice.enabled) stringResource(R.string.cat_announces) else null
            val sunrise = if (perAlarm && alarm.sunrise.enabled) stringResource(R.string.cat_sunrise) else null
            listOfNotNull(tone, voice, sunrise).joinToString(separator)
        }
        AlarmCategory.VOLUME -> {
            val v = alarm.volume
            val level = if (v.level == 0) stringResource(R.string.cat_system_volume) else "${v.level}%"
            val fade = if (v.rampSeconds > 0) stringResource(R.string.cat_fade_in, fadeLabel(v.rampSeconds)) else null
            val loud = if (v.extraLoudStrength > 0) stringResource(R.string.cat_extra_loud, v.extraLoudStrength) else null
            val vibration = if (alarm.vibration.enabled) stringResource(vibrationLabel(alarm.vibration.pattern)) else stringResource(R.string.cat_vibration_off)
            listOfNotNull(level, loud, fade, vibration).joinToString(separator)
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
        AlarmCategory.PROTECTION -> {
            val a = alarm.antiOversleep
            val hold = if (a.holdToDismissSeconds > 0) stringResource(R.string.cat_hold, stringResource(R.string.unit_seconds, a.holdToDismissSeconds)) else null
            val backup = if (a.backupEnabled) stringResource(R.string.cat_backup_in, minutesLabel(a.backupDelayMinutes)) else null
            val lock = if (a.editLockMinutes > 0) stringResource(R.string.cat_edit_lock, minutesLabel(a.editLockMinutes)) else null
            val noEmergency = if (!a.emergencyStopEnabled) stringResource(R.string.cat_no_emergency_stop) else null
            listOfNotNull(noEmergency, backup, hold, lock).ifEmpty { listOf(stringResource(R.string.cat_standard)) }.joinToString(separator)
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
