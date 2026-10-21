package com.riyadm.wakenudge.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.components.SettingsGroup
import com.riyadm.wakenudge.ui.components.SwitchRow
import com.riyadm.wakenudge.ui.components.TextInputDialog
import com.riyadm.wakenudge.ui.util.Formatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Days that repeating alarms skip: typed-in dates and, optionally, all-day events from calendars on the phone. */
@Composable
fun ExceptionsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: ExceptionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var pendingDay by remember { mutableStateOf<Long?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onPermissionResult()
        if (granted) viewModel.setCalendarEnabled(true)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.exceptions_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                Text(stringResource(R.string.exceptions_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                SectionTitle(stringResource(R.string.exceptions_calendar))
                SettingsGroup {
                    item { shape ->
                        SwitchRow(
                            title = stringResource(R.string.exceptions_calendar_switch), subtitle = stringResource(R.string.exceptions_calendar_hint),
                            checked = state.calendarEnabled && state.hasCalendarPermission,
                            onCheckedChange = { on ->
                                if (on && !state.hasCalendarPermission) permission.launch(Manifest.permission.READ_CALENDAR) else viewModel.setCalendarEnabled(on)
                            },
                            shape = shape,
                        )
                    }
                }
            }
            if (state.calendarEnabled && state.hasCalendarPermission) {
                if (state.calendars.isEmpty()) {
                    item { Text(stringResource(R.string.exceptions_no_calendars), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp)) }
                }
                items(state.calendars, key = { "cal-${it.id}" }) { cal ->
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = cal.id in state.selectedCalendarIds, onCheckedChange = { viewModel.toggleCalendar(cal.id, it) })
                            Column(Modifier.weight(1f)) {
                                Text(cal.name, style = MaterialTheme.typography.bodyLarge)
                                Text(cal.account, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    Text(
                        pluralStringResource(R.plurals.exceptions_calendar_days, state.calendarHolidayDays, state.calendarHolidayDays),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            item { SectionTitle(stringResource(R.string.exceptions_dates)) }
            if (state.dates.isEmpty()) {
                item { Text(stringResource(R.string.exceptions_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp)) }
            }
            items(state.dates, key = { "d-${it.epochDay}" }) { d ->
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().animateItem()) {
                    Row(Modifier.padding(start = 20.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Formatters.dateShort(LocalDate.ofEpochDay(d.epochDay)), style = MaterialTheme.typography.titleSmall)
                            if (d.label.isNotBlank()) Text(d.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.removeDate(d.epochDay) }) { Icon(Icons.Filled.Close, stringResource(R.string.cd_remove)) }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Filled.Add, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.exceptions_add))
                }
            }
        }
    }
    if (picking) {
        val state0 = rememberDatePickerState(initialSelectedDateMillis = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state0.selectedDateMillis?.let { pendingDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    picking = false
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.action_cancel)) } },
        ) { DatePicker(state0) }
    }
    pendingDay?.let { day ->
        TextInputDialog(
            title = stringResource(R.string.exceptions_label_title, Formatters.dateShort(LocalDate.ofEpochDay(day))), initial = "",
            label = stringResource(R.string.exceptions_label), maxLength = 40,
            onConfirm = { viewModel.addDate(day, it) }, onDismiss = { pendingDay = null },
        )
    }
}

