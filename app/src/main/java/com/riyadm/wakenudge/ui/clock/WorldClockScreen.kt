package com.riyadm.wakenudge.ui.clock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.WorldClockCity
import com.riyadm.wakenudge.ui.components.AnimatedClockText
import com.riyadm.wakenudge.ui.components.topLevelInsets
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.ClockType
import com.riyadm.wakenudge.ui.theme.PolygonShape
import com.riyadm.wakenudge.ui.util.Formatters
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

@Composable
fun WorldClockScreen(modifier: Modifier = Modifier, viewModel: WorldClockViewModel = hiltViewModel()) {
    val cities by viewModel.cities.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val is24 = rememberIs24Hour(settings.timeFormat)
    val context = LocalContext.current
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var adding by remember { mutableStateOf(false) }
    // "Plan ahead": scrub the hour offset to see what time it will be everywhere.
    var offsetHours by remember { mutableFloatStateOf(0f) }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000 - value % 1000)
        }
    }
    val scrubbed = Instant.ofEpochMilli(now).plus(offsetHours.toLong(), ChronoUnit.HOURS)
    val local = ZoneId.systemDefault()

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = topLevelInsets(),
        topBar = { LargeTopAppBar(title = { Text(stringResource(R.string.clock_title)) }, scrollBehavior = scroll) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { adding = true },
                shape = PolygonShape(Blobs.Cookie),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(84.dp),
            ) { Icon(Icons.Filled.Add, stringResource(R.string.clock_add_city), Modifier.size(32.dp)) }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                ClockCard(
                    title = stringResource(R.string.clock_local), subtitle = Formatters.fullDate(scrubbed.atZone(local)),
                    zone = local, instant = scrubbed, is24 = is24, showSeconds = settings.showSeconds && offsetHours == 0f,
                    primary = true, onRemove = null,
                )
            }
            item {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.clock_plan_ahead), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Text(
                                if (offsetHours == 0f) stringResource(R.string.clock_now) else stringResource(R.string.clock_hours_ahead, offsetHours.toInt()),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Slider(value = offsetHours, onValueChange = { offsetHours = it }, valueRange = -12f..48f, steps = 59)
                    }
                }
            }
            val list = cities
            if (list != null && list.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(stringResource(R.string.clock_popular), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorldClockViewModel.Popular.forEach { id ->
                                AssistChip(onClick = { viewModel.add(id) }, label = { Text(Formatters.cityName(id)) })
                            }
                        }
                    }
                }
            }
            items(list.orEmpty(), key = { it.zoneId }) { city ->
                val zone = runCatching { ZoneId.of(city.zoneId) }.getOrNull() ?: return@items
                val zoned = scrubbed.atZone(zone)
                ClockCard(
                    title = city.label,
                    subtitle = Formatters.relativeDay(context, zoned, scrubbed.atZone(local)) + "  ·  " + Formatters.offsetDifference(context, local, zone, scrubbed) + "  ·  " + Formatters.regionName(city.zoneId),
                    zone = zone, instant = scrubbed, is24 = is24, showSeconds = false, primary = false,
                    onRemove = { viewModel.remove(city.zoneId) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
    if (adding) {
        AddCitySheet(
            zones = viewModel.allZones,
            onPick = { viewModel.add(it); adding = false },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun ClockCard(
    title: String,
    subtitle: String,
    zone: ZoneId,
    instant: Instant,
    is24: Boolean,
    showSeconds: Boolean,
    primary: Boolean,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val zoned = instant.atZone(zone)
    val daytime = zoned.hour in 6..17
    val (digits, suffix) = TimeText.parts(zoned.toLocalTime(), is24)
    Surface(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(if (primary) 36.dp else 28.dp),
        color = if (primary) scheme.primaryContainer else scheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 2)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                AnimatedClockText(digits, if (primary) ClockType.Card else ClockType.Card.copy(fontSize = androidx.compose.ui.unit.TextUnit(40f, androidx.compose.ui.unit.TextUnitType.Sp)), spokenText = TimeText.format(zoned.toLocalTime(), is24))
                if (suffix.isNotEmpty()) Text(suffix, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            }
            Icon(if (daytime) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, null, tint = scheme.tertiary, modifier = Modifier.padding(start = 12.dp))
            if (onRemove != null) {
                IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, stringResource(R.string.cd_remove_city, title)) }
            }
        }
    }
}

@Composable
private fun AddCitySheet(zones: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, zones) {
        val q = query.trim().replace(' ', '_').lowercase()
        if (q.isEmpty()) WorldClockViewModel.Popular else zones.filter { it.lowercase().contains(q) }.take(80)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                label = { Text(stringResource(R.string.clock_search)) }, modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                items(results, key = { it }) { id ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(onClick = { onPick(id) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(Formatters.cityName(id), style = MaterialTheme.typography.titleMedium)
                                Text(Formatters.regionName(id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
