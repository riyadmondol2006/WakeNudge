package com.riyadm.wakenudge.ui.ring

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.domain.scheduling.SnoozePolicy
import com.riyadm.wakenudge.engine.RingKind
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession
import com.riyadm.wakenudge.ui.components.AnimatedClockText
import com.riyadm.wakenudge.ui.components.rememberReduceMotion
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.PolygonShape
import com.riyadm.wakenudge.ui.theme.WakeMotion
import com.riyadm.wakenudge.ui.util.Formatters
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import kotlinx.coroutines.delay

private enum class RingMode { Home, DismissMissions, SnoozeMissions }

/** What the ring screen needs from its host; keeps the screen a pure function of state + callbacks. */
class RingActions(
    val onSnooze: () -> Unit,
    val onDismiss: () -> Unit,
    val onEmergencyStop: () -> Unit,
    val onAddMinute: () -> Unit,
    val onHide: () -> Unit,
    val onInteraction: () -> Unit,
    val onMissionProgress: (Int) -> Unit,
)

@Composable
fun RingScreen(
    session: RingSession,
    settings: AppSettings,
    actions: RingActions,
    missionContent: @Composable (missions: List<MissionConfig>, onInteraction: () -> Unit, onProgress: (Int) -> Unit, onComplete: () -> Unit, firstMission: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(RingMode.Home) }
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    val sunrise = session.phase == RingPhase.SUNRISE

    // Sunrise: glow fraction grows from the moment the session starts until the alarm's lead time is over.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000 - value % 1000)
        }
    }
    val glow = if (sunrise) {
        val total = (session.alarm.sunrise.leadMinutes * 60_000L).coerceAtLeast(1)
        ((now - session.startedAt).toFloat() / total).coerceIn(0.05f, 1f)
    } else {
        1f
    }

    val top by animateColorAsState(
        when {
            session.intensified -> scheme.errorContainer
            sunrise -> lerp(scheme.background, scheme.primaryContainer, glow)
            else -> scheme.primaryContainer
        },
        tween(1200), label = "ringTop",
    )
    val bottom by animateColorAsState(
        when {
            session.intensified -> scheme.tertiaryContainer
            sunrise -> lerp(scheme.background, scheme.tertiaryContainer, glow)
            else -> scheme.tertiaryContainer
        },
        tween(1200), label = "ringBottom",
    )
    // The gradient's lower end can be light or dark depending on the wallpaper palette; pick readable text for it.
    val onBottom = if (bottom.luminance() > 0.5f) scheme.onTertiaryContainer else scheme.onPrimaryContainer
    val pulse = rememberInfiniteTransition(label = "ring-pulse")
    val spin by pulse.animateFloat(0f, 360f, infiniteRepeatable(tween(50_000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val breathe by pulse.animateFloat(
        0.92f, 1.08f,
        infiniteRepeatable(tween(if (session.intensified) 600 else 2400, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )

    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, bottom)))) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(360.dp)
                .scale(if (reduceMotion) 1f else breathe)
                .rotate(if (reduceMotion) 0f else spin)
                .clip(PolygonShape(Blobs.Sunny))
                .background((if (session.intensified) scheme.error else scheme.primary).copy(alpha = 0.10f + 0.08f * glow)),
        )
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val wide = maxWidth > maxHeight && maxWidth > 600.dp
            val viewport = maxHeight
            if (mode != RingMode.Home) {
                // Missions always use this one layout, whatever the window's shape. A wide layout used to measure them
                // inside a scrolling column, which gave the mission no height at all, and switching layouts on rotation
                // (or when a keyboard changed the window's shape) threw away the mission in progress.
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp)) {
                    MissionTopBar(session, settings, now, mode, onBack = { mode = RingMode.Home })
                    Controls(session, settings, mode, { mode = it }, actions, missionContent, onBottom, Modifier.weight(1f))
                }
            } else if (wide) {
                Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                        Header(session, settings, now)
                    }
                    Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
                        Controls(session, settings, mode, { mode = it }, actions, missionContent, onBottom)
                    }
                }
            } else {
                // Scrollable so that large system fonts or a small screen can never push a control off the bottom.
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = viewport).padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Header(session, settings, now, compact = false)
                    Controls(session, settings, mode, { mode = it }, actions, missionContent, onBottom)
                }
            }
        }
    }
}

@Composable
private fun Header(session: RingSession, settings: AppSettings, now: Long, compact: Boolean = false) {
    val is24 = rememberIs24Hour(settings.timeFormat)
    val zoned = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
    val (digits, suffix) = TimeText.parts(zoned.toLocalTime(), is24)
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        // Wraps instead of running off the edge when two badges meet a large font.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            badgeTexts(session).forEach { Badge(it) }
        }
        if (session.phase == RingPhase.SUNRISE) {
            Text(stringResource(R.string.ring_sunrise), style = MaterialTheme.typography.titleMedium, color = scheme.onPrimaryContainer, modifier = Modifier.padding(top = 8.dp))
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = if (compact) 4.dp else 32.dp)) {
            AnimatedClockText(
                digits,
                if (compact) ClockType.Card else ClockType.Hero,
                color = scheme.onPrimaryContainer,
                spokenText = TimeText.format(zoned.toLocalTime(), is24),
            )
            if (suffix.isNotEmpty()) {
                Text(suffix, style = ClockType.CardSuffix, color = scheme.onPrimaryContainer, modifier = Modifier.padding(start = 8.dp, bottom = if (compact) 10.dp else 22.dp))
            }
        }
        if (!compact) {
            Text(Formatters.fullDate(zoned), style = MaterialTheme.typography.titleMedium, color = scheme.onPrimaryContainer.copy(alpha = 0.85f))
        }
        val title = when {
            session.isTimer -> session.alarm.label.ifBlank { stringResource(R.string.ring_timer_done) }
            else -> session.alarm.label
        }
        if (title.isNotBlank()) {
            // Above a mission the label gets one line: a 60-character label at a large font size would otherwise take
            // the room the mission needs.
            Text(
                title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                color = scheme.onPrimaryContainer, textAlign = TextAlign.Center, modifier = Modifier.padding(top = if (compact) 4.dp else 12.dp),
                maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Why this ring is special (a test, the backup, a re-ring) and how often it was snoozed, in display order. */
@Composable
private fun badgeTexts(session: RingSession): List<String> = buildList {
    when {
        session.isTest -> add(stringResource(R.string.ring_badge_test))
        session.kind == RingKind.BACKUP -> add(stringResource(R.string.ring_badge_backup))
        session.kind == RingKind.WAKE_CHECK_RERING -> add(stringResource(R.string.ring_badge_recheck))
    }
    if (session.snoozeCount > 0 && !session.isTimer) {
        add(pluralStringResource(R.plurals.ring_badge_snoozed, session.snoozeCount, session.snoozeCount))
    }
}

/** Plain information, so a solid pill rather than a (disabled, faint, "button") chip. */
@Composable
private fun Badge(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/**
 * The header above a mission: Back, the time and the label in one row, and what the mission is for under them. The
 * mission keeps the height it needs (a maths keypad, a puzzle board) even on a small phone.
 */
@Composable
private fun MissionTopBar(session: RingSession, settings: AppSettings, now: Long, mode: RingMode, onBack: () -> Unit) {
    val is24 = rememberIs24Hour(settings.timeFormat)
    val time = TimeText.format(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalTime(), is24)
    val scheme = MaterialTheme.colorScheme
    val purpose = stringResource(if (mode == RingMode.SnoozeMissions) R.string.ring_missions_snooze else R.string.ring_missions_dismiss)
    val notes = (listOf(purpose) + badgeTexts(session)).joinToString(" · ")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.action_back))
        }
        Column(Modifier.weight(1f).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
            Text(
                listOfNotNull(time, session.alarm.label.takeIf { it.isNotBlank() }).joinToString(" · "),
                style = MaterialTheme.typography.titleLarge, color = scheme.onPrimaryContainer,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                notes, style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
                textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Controls(
    session: RingSession,
    settings: AppSettings,
    mode: RingMode,
    setMode: (RingMode) -> Unit,
    actions: RingActions,
    missionContent: @Composable (List<MissionConfig>, () -> Unit, (Int) -> Unit, () -> Unit, Int) -> Unit,
    onBottom: Color,
    modifier: Modifier = Modifier,
) {
    val alarm = session.alarm
    val haptics = LocalWakeHaptics.current
    AnimatedContent(
        targetState = mode,
        modifier = modifier,
        transitionSpec = {
            (slideInVertically(WakeMotion.defaultSpatial()) { it / 4 } + fadeIn(WakeMotion.defaultEffects()))
                .togetherWith(slideOutVertically(WakeMotion.defaultSpatial()) { -it / 4 } + fadeOut(WakeMotion.fastEffects()))
        },
        label = "ring-mode",
    ) { current ->
        when (current) {
            RingMode.Home -> Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (session.phase == RingPhase.SUNRISE) {
                    TextButton(onClick = actions.onHide) { Text(stringResource(R.string.ring_sunrise_hide)) }
                    return@Column
                }
                if (session.isTimer) {
                    val addMinute = stringResource(R.string.cd_add_minute)
                    FilledTonalButton(
                        onClick = { haptics.click(); actions.onAddMinute() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics { contentDescription = addMinute },
                    ) {
                        Text(stringResource(R.string.action_add_minute), style = MaterialTheme.typography.titleMedium)
                    }
                    SlideToDismiss(
                        label = stringResource(R.string.ring_slide_stop),
                        actionDescription = stringResource(R.string.ring_stop_timer),
                        onDismiss = actions.onDismiss,
                    )
                    return@Column
                }
                val canSnooze = SnoozePolicy.canSnooze(alarm.snooze, session.snoozeCount)
                if (canSnooze) {
                    val minutes = SnoozePolicy.nextIntervalMinutes(alarm.snooze, session.snoozeCount)
                    val left = SnoozePolicy.remaining(alarm.snooze, session.snoozeCount)
                    FilledTonalButton(
                        onClick = {
                            haptics.click()
                            actions.onInteraction()
                            if (alarm.snoozeMissions.isNotEmpty()) setMode(RingMode.SnoozeMissions) else actions.onSnooze()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    ) {
                        Icon(Icons.Filled.Snooze, null, Modifier.padding(end = 12.dp))
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(
                                stringResource(if (alarm.snoozeMissions.isNotEmpty()) R.string.ring_solve_to_snooze else R.string.ring_snooze_minutes, minutes),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (left != null) Text(pluralStringResource(R.plurals.ring_snoozes_left, left, left), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (alarm.snooze.enabled) {
                    Text(stringResource(R.string.ring_no_more_snoozes), style = MaterialTheme.typography.bodyMedium, color = onBottom)
                }
                when {
                    session.hasMissions -> Button(
                        onClick = { haptics.click(); actions.onInteraction(); setMode(RingMode.DismissMissions) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    ) { Text(stringResource(R.string.ring_solve_to_dismiss), style = MaterialTheme.typography.titleMedium) }

                    alarm.antiOversleep.holdToDismissSeconds > 0 -> HoldButton(
                        label = stringResource(R.string.ring_hold_to_dismiss, alarm.antiOversleep.holdToDismissSeconds),
                        holdSeconds = alarm.antiOversleep.holdToDismissSeconds,
                        actionDescription = stringResource(R.string.ring_dismiss_alarm),
                        onComplete = actions.onDismiss,
                    )

                    else -> SlideToDismiss(
                        label = stringResource(R.string.ring_slide_dismiss),
                        actionDescription = stringResource(R.string.ring_dismiss_alarm),
                        onDismiss = actions.onDismiss,
                    )
                }
                if (alarm.antiOversleep.emergencyStopEnabled) {
                    EmergencyStop(settings.emergencyStopHoldSeconds, actions.onEmergencyStop, onBottom)
                }
            }

            // Back and the purpose of the missions are in the MissionTopBar above.
            RingMode.DismissMissions, RingMode.SnoozeMissions -> Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val list = if (current == RingMode.SnoozeMissions) alarm.snoozeMissions else alarm.missions
                    missionContent(
                        list,
                        actions.onInteraction,
                        { if (current == RingMode.DismissMissions) actions.onMissionProgress(it) },
                        { if (current == RingMode.SnoozeMissions) actions.onSnooze() else actions.onDismiss() },
                        // Going back to the alarm (or the screen being rebuilt) must not cost the missions already done.
                        if (current == RingMode.DismissMissions) session.missionsDone.coerceIn(0, list.size) else 0,
                    )
                }
                if (alarm.antiOversleep.emergencyStopEnabled) {
                    EmergencyStop(settings.emergencyStopHoldSeconds, actions.onEmergencyStop, onBottom, Modifier.padding(top = 8.dp), compact = true)
                }
            }
        }
    }
}

/**
 * The always-available escape hatch. Sighted users hold it; screen-reader users double-tap and then confirm in a
 * dialog. It bypasses missions and is recorded honestly in the history as an emergency stop.
 */
@Composable
private fun EmergencyStop(holdSeconds: Int, onStop: () -> Unit, hintColor: Color, modifier: Modifier = Modifier, compact: Boolean = false) {
    var confirm by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HoldButton(
            // Under a mission the button says how to use it itself, which saves the hint line.
            label = if (compact) stringResource(R.string.ring_emergency_stop_hold, holdSeconds) else stringResource(R.string.ring_emergency_stop),
            holdSeconds = holdSeconds.coerceIn(1, 10),
            actionDescription = stringResource(R.string.ring_emergency_stop),
            onComplete = onStop,
            onAccessibilityClick = { confirm = true },
            container = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            content = MaterialTheme.colorScheme.onSurface,
            fill = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.widthIn(max = 420.dp),
            height = 52.dp,
        )
        if (!compact) {
            Text(
                stringResource(R.string.ring_emergency_hint, holdSeconds),
                style = MaterialTheme.typography.bodySmall, color = hintColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.ring_emergency_title)) },
            text = { Text(stringResource(R.string.ring_emergency_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; onStop() }) { Text(stringResource(R.string.ring_emergency_confirm)) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
