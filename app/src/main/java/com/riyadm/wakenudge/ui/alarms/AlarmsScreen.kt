package com.riyadm.wakenudge.ui.alarms

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.ui.components.AnimatedClockText
import com.riyadm.wakenudge.ui.components.HeroCard
import com.riyadm.wakenudge.ui.components.rememberReduceMotion
import com.riyadm.wakenudge.ui.components.topLevelInsets
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.PolygonShape
import com.riyadm.wakenudge.ui.theme.WakeMotion
import com.riyadm.wakenudge.ui.theme.pressScale
import com.riyadm.wakenudge.ui.theme.rememberInteractionSource
import com.riyadm.wakenudge.ui.util.Formatters
import com.riyadm.wakenudge.ui.util.rememberIs24Hour

@Composable
fun AlarmsScreen(
    onEditAlarm: (Long) -> Unit,
    onOpenSleep: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGroups: () -> Unit,
    modifier: Modifier = Modifier,
    healthBadge: @Composable () -> Unit = {},
    viewModel: AlarmsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            snackbar.currentSnackbarData?.dismiss()
            when (event) {
                is AlarmsEvent.Locked -> snackbar.showSnackbar(context.getString(R.string.snackbar_locked, event.minutes))
                is AlarmsEvent.Skipped -> snackbar.showSnackbar(
                    context.getString(if (event.epochDay == null) R.string.snackbar_switched_off else R.string.snackbar_skipped),
                )
                is AlarmsEvent.Deleted -> {
                    val result = snackbar.showSnackbar(
                        message = context.getString(R.string.snackbar_deleted),
                        actionLabel = context.getString(R.string.snackbar_undo),
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(event.alarm)
                }
            }
        }
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = topLevelInsets(),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.alarms_title)) },
                scrollBehavior = scroll,
                actions = {
                    IconButton(onClick = onOpenSleep) { Icon(Icons.Outlined.Bedtime, stringResource(R.string.cd_sleep_planner)) }
                    IconButton(onClick = onOpenHealth) {
                        Box { Icon(Icons.Outlined.MonitorHeart, stringResource(R.string.cd_alarm_health)); healthBadge() }
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.cd_settings)) }
                },
            )
        },
        floatingActionButton = {
            val interaction = rememberInteractionSource()
            FloatingActionButton(
                onClick = { onEditAlarm(0) },
                interactionSource = interaction,
                shape = PolygonShape(Blobs.Cookie),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier.size(84.dp).pressScale(interaction, 0.9f),
            ) { Icon(Icons.Filled.Add, stringResource(R.string.alarms_add), Modifier.size(32.dp)) }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.fillMaxSize())
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 120.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") {
                NextAlarmHero(
                    state = state,
                    onResume = { viewModel.setVacation(false) },
                    onStartVacation = { viewModel.setVacation(true, null) },
                )
            }
            if (state.groups.isNotEmpty()) {
                item(key = "groups") {
                    GroupStrip(
                        state = state,
                        onSelect = viewModel::setFilter,
                        onToggleGroup = viewModel::setGroupEnabled,
                        onProfileOnly = viewModel::activateProfileOnly,
                        onManage = onOpenGroups,
                    )
                }
            }
            if (state.items.isEmpty()) {
                item(key = "empty") { EmptyAlarms(filtered = state.filterGroupId != null) }
            } else {
                items(state.items, key = { it.alarm.id }) { item ->
                    AlarmCard(
                        item = item,
                        settings = state.settings,
                        now = state.now,
                        onClick = { onEditAlarm(item.alarm.id) },
                        onToggle = { viewModel.toggle(item, it) },
                        onSkipNext = { viewModel.skipNext(item) },
                        onClearSkip = { viewModel.clearSkip(item) },
                        onDuplicate = { viewModel.duplicate(item) },
                        onDelete = { viewModel.delete(item) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
            if (state.groups.isEmpty() && state.items.isNotEmpty()) {
                item(key = "groups-hint") {
                    TextButton(onClick = onOpenGroups, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FolderSpecial, null, Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.groups_create_hint))
                    }
                }
            }
        }
    }
}

@Composable
private fun NextAlarmHero(state: AlarmsUiState, onResume: () -> Unit, onStartVacation: () -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val vacation = state.settings.vacation
    val is24 = rememberIs24Hour(state.settings.timeFormat)
    val reduce = rememberReduceMotion()
    val spin by rememberInfiniteTransition(label = "hero-spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(60_000, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )

    HeroCard(
        color = if (vacation.enabled) scheme.tertiaryContainer else scheme.primaryContainer,
        modifier = Modifier.animateContentSize(WakeMotion.defaultSpatial()),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 56.dp, y = (-56).dp)
                    .size(190.dp)
                    .rotate(if (reduce) 0f else spin)
                    .clip(PolygonShape(Blobs.Cookie))
                    .background((if (vacation.enabled) scheme.tertiary else scheme.primary).copy(alpha = 0.14f)),
            )
            Column {
                val next = state.next
                when {
                    vacation.enabled -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.BeachAccess, null, tint = scheme.onTertiaryContainer)
                            Text(
                                stringResource(R.string.alarms_vacation_on), style = MaterialTheme.typography.titleLarge,
                                color = scheme.onTertiaryContainer, modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                        Text(
                            vacation.resumeEpochDay?.let { stringResource(R.string.alarms_vacation_until, Formatters.dateShort(java.time.LocalDate.ofEpochDay(it))) }
                                ?: stringResource(R.string.alarms_vacation_indefinite),
                            style = MaterialTheme.typography.bodyLarge, color = scheme.onTertiaryContainer,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Button(onClick = onResume, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.alarms_resume)) }
                    }
                    next == null -> {
                        Text(stringResource(R.string.alarms_next_title), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
                        Text(
                            stringResource(R.string.alarms_none), style = MaterialTheme.typography.headlineMedium,
                            color = scheme.onPrimaryContainer, modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            stringResource(R.string.alarms_none_hint), style = MaterialTheme.typography.bodyLarge,
                            color = scheme.onPrimaryContainer.copy(alpha = 0.8f), modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    else -> {
                        val at = next.nextAt!!
                        val (digits, suffix) = TimeText.parts(at.toLocalTime(), is24)
                        Text(stringResource(R.string.alarms_next_title), style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
                        Row(verticalAlignment = Alignment.Bottom) {
                            AnimatedClockText(digits, ClockType.Card.copy(fontSize = androidx.compose.ui.unit.TextUnit(64f, androidx.compose.ui.unit.TextUnitType.Sp)), color = scheme.onPrimaryContainer)
                            if (suffix.isNotEmpty()) {
                                Text(suffix, style = ClockType.CardSuffix, color = scheme.onPrimaryContainer, modifier = Modifier.padding(start = 6.dp, bottom = 12.dp))
                            }
                        }
                        Text(
                            Formatters.untilText(context, state.now.toInstant().toEpochMilli(), at.toInstant().toEpochMilli()) +
                                "  ·  " + Formatters.relativeDay(context, at, state.now),
                            style = MaterialTheme.typography.titleMedium, color = scheme.onPrimaryContainer,
                        )
                        if (next.alarm.label.isNotBlank()) {
                            Text(
                                next.alarm.label, style = MaterialTheme.typography.bodyLarge,
                                color = scheme.onPrimaryContainer.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
                if (!vacation.enabled) {
                    TextButton(onClick = onStartVacation, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Outlined.BeachAccess, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.alarms_pause_all), Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupStrip(
    state: AlarmsUiState,
    onSelect: (Long?) -> Unit,
    onToggleGroup: (Long, Boolean) -> Unit,
    onProfileOnly: (Long) -> Unit,
    onManage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = state.filterGroupId == null, onClick = { onSelect(null) },
                    label = { Text(stringResource(R.string.filter_all)) },
                )
            }
            items(state.groups, key = { it.id }) { g ->
                FilterChip(
                    selected = state.filterGroupId == g.id, onClick = { onSelect(g.id) },
                    label = { Text(g.name) },
                )
            }
            item {
                androidx.compose.material3.AssistChip(
                    onClick = onManage,
                    label = { Text(stringResource(R.string.groups_manage)) },
                    leadingIcon = { Icon(Icons.Outlined.FolderSpecial, null, Modifier.size(18.dp)) },
                )
            }
        }
        val selected = state.groups.firstOrNull { it.id == state.filterGroupId }
        if (selected != null) {
            val count = state.items.size
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(selected.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        pluralStringResource(R.plurals.group_alarm_count, count, count),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onProfileOnly(selected.id) }) { Text(stringResource(R.string.group_only_this)) }
                Switch(checked = selected.enabled, onCheckedChange = { onToggleGroup(selected.id, it) })
            }
        }
    }
}

@Composable
private fun EmptyAlarms(filtered: Boolean) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(120.dp).clip(PolygonShape(Blobs.Sunny)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Bedtime, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(if (filtered) R.string.empty_group_title else R.string.empty_alarms_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(if (filtered) R.string.empty_group_body else R.string.empty_alarms_body),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
