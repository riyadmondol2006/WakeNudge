package com.riyadm.wakenudge.ui.habits

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.habits.Habit
import com.riyadm.wakenudge.domain.habits.HabitStreakCalculator
import com.riyadm.wakenudge.ui.components.ConfirmDialog
import com.riyadm.wakenudge.ui.components.DayChip
import com.riyadm.wakenudge.ui.components.SwitchRow
import com.riyadm.wakenudge.ui.components.TimeEntryDialog
import com.riyadm.wakenudge.ui.components.ValueRow
import com.riyadm.wakenudge.ui.theme.PolygonShape
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.domain.stats.WakeReportCalculator
import com.riyadm.wakenudge.ui.util.currentLocale
import com.riyadm.wakenudge.ui.util.rememberIs24Hour
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle

@Composable
fun HabitsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: HabitsViewModel = hiltViewModel()) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val firstDay = settings.firstDayOfWeek(WeekFields.of(locale).firstDayOfWeek)
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val snackbar = remember { SnackbarHostState() }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Habit?>(null) }
    var deleting by remember { mutableStateOf<Habit?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refresh() }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh() }
        lifecycle.addObserver(observer)
        viewModel.refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val limitText = stringResource(R.string.habits_limit)
    LaunchedEffect(viewModel) {
        viewModel.limitMessage.collect { snackbar.showSnackbar(limitText) }
    }

    fun openNew() {
        editing = null
        editorOpen = true
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.habits_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (data?.habits?.isNotEmpty() == true) {
                FloatingActionButton(
                    onClick = ::openNew,
                    shape = PolygonShape(Blobs.Cookie),
                ) { Icon(Icons.Filled.Add, stringResource(R.string.habits_add)) }
            }
        },
    ) { padding ->
        val habits = data?.habits
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!notifications && habits?.any { it.reminderMinutes != null } == true) {
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(24.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Icon(Icons.Outlined.NotificationsOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                stringResource(R.string.habits_notifications_blocked),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            TextButton(onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                                }
                            }) { Text(stringResource(R.string.habits_notification_settings)) }
                        }
                    }
                }
            }
            if (habits == null) {
                item { Box(Modifier.fillMaxWidth().padding(48.dp)) }
            } else if (habits.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.habits_empty_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.habits_empty_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = ::openNew) {
                            Icon(Icons.Filled.Add, null)
                            Text(stringResource(R.string.habits_add), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            } else {
                items(habits, key = { it.id }) { habit ->
                    HabitCard(
                        habit = habit,
                        data = data!!,
                        today = today,
                        firstDay = firstDay,
                        canMoveUp = habits.indexOf(habit) > 0,
                        canMoveDown = habits.indexOf(habit) < habits.lastIndex,
                        onToggle = viewModel::toggle,
                        onMove = { upwards -> viewModel.move(habit.id, upwards) },
                        onEdit = { editing = habit; editorOpen = true },
                        onDelete = { deleting = habit },
                    )
                }
            }
        }
    }

    if (editorOpen) {
        HabitEditorSheet(
            habit = editing,
            today = today,
            is24 = rememberIs24Hour(settings.timeFormat),
            onDismiss = { editorOpen = false },
            onSave = { habit ->
                if (habit.reminderMinutes != null && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                viewModel.save(habit) { editorOpen = false }
            },
        )
    }
    deleting?.let { habit ->
        ConfirmDialog(
            title = stringResource(R.string.habits_delete_title),
            text = stringResource(R.string.habits_delete_body, habit.name),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { viewModel.delete(habit.id) },
            onDismiss = { deleting = null },
            destructive = true,
        )
    }
}

@Composable
private fun HabitCard(
    habit: Habit,
    data: com.riyadm.wakenudge.domain.habits.HabitData,
    today: LocalDate,
    firstDay: DayOfWeek,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: (Long, LocalDate) -> Unit,
    onMove: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val done = data.doneDates(habit.id)
    val streak = HabitStreakCalculator.calculate(habit, done, today)
    val weekStart = WakeReportCalculator.weekStart(today, firstDay)
    val todayDone = today.toEpochDay() in done
    val dueToday = habit.isDueOn(today)
    val colour = habitColour(habit.colorIndex)
    val todayDescription = stringResource(
        if (todayDone) R.string.habits_mark_not_done else R.string.habits_mark_done,
        habit.name,
    )
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(CircleShape).background(colour.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Text(habit.icon, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(habit.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Column {
                        HabitStreakBadge(streak.current, colour)
                        Text(
                            pluralStringResource(R.plurals.habits_best_streak, streak.best, streak.best),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Surface(
                    modifier = Modifier.size(52.dp).semantics { contentDescription = todayDescription },
                    shape = CircleShape,
                    color = if (todayDone) colour else MaterialTheme.colorScheme.surfaceContainerHighest,
                    onClick = { onToggle(habit.id, today) },
                    enabled = dueToday,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Check,
                            null,
                            tint = if (todayDone && colour.luminance() <= 0.45f) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HabitWeekRow(
                habit = habit,
                weekStart = weekStart,
                today = today,
                done = done,
                onToggle = { onToggle(habit.id, it) },
                modifier = Modifier.padding(top = 14.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { onMove(true) }, enabled = canMoveUp) { Icon(Icons.Outlined.ArrowUpward, stringResource(R.string.habits_move_up, habit.name)) }
                IconButton(onClick = { onMove(false) }, enabled = canMoveDown) { Icon(Icons.Outlined.ArrowDownward, stringResource(R.string.habits_move_down, habit.name)) }
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.habits_edit, habit.name)) }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, stringResource(R.string.habits_delete, habit.name), tint = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun HabitEditorSheet(
    habit: Habit?,
    today: LocalDate,
    is24: Boolean,
    onDismiss: () -> Unit,
    onSave: (Habit) -> Unit,
) {
    var name by remember(habit?.id) { mutableStateOf(habit?.name.orEmpty()) }
    var icon by remember(habit?.id) { mutableStateOf(habit?.icon ?: Habit.DEFAULT_ICON) }
    var colour by remember(habit?.id) { mutableIntStateOf(habit?.colorIndex ?: 0) }
    var days by remember(habit?.id) { mutableIntStateOf(habit?.daysMask ?: Habit.EVERY_DAY_MASK) }
    var reminderEnabled by remember(habit?.id) { mutableStateOf(habit?.reminderMinutes != null) }
    var reminderMinutes by remember(habit?.id) { mutableIntStateOf(habit?.reminderMinutes ?: 8 * 60) }
    var timeDialog by remember { mutableStateOf(false) }
    val locale = currentLocale()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text(stringResource(if (habit == null) R.string.habits_new else R.string.habits_edit_title), style = MaterialTheme.typography.headlineSmall) }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = safeHabitText(it, Habit.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.habits_name)) },
                    singleLine = true,
                    isError = name.isBlank(),
                    supportingText = { if (name.isBlank()) Text(stringResource(R.string.habits_name_error)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(stringResource(R.string.habits_icon), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = icon,
                    onValueChange = { icon = safeHabitText(it, Habit.MAX_ICON_LENGTH) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    HabitIcons.forEach { choice ->
                        FilledTonalButton(onClick = { icon = choice }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
                            Text(choice, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
            item {
                Text(stringResource(R.string.habits_colour), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    HabitPalette.forEachIndexed { index, colourValue ->
                        val description = stringResource(R.string.habits_colour_choice, index + 1)
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(colourValue)
                                .then(if (colour == index) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .selectable(selected = colour == index, role = Role.RadioButton, onClick = { colour = index })
                                .semantics { contentDescription = description },
                        )
                    }
                }
            }
            item {
                Text(stringResource(R.string.habits_days), style = MaterialTheme.typography.titleSmall)
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    DayOfWeek.entries.chunked(4).forEach { week ->
                        Row(Modifier.fillMaxWidth()) {
                            week.forEach { day ->
                                val bit = Habit.bitOf(day)
                                DayChip(
                                    label = day.getDisplayName(TextStyle.NARROW, locale),
                                    fullName = day.getDisplayName(TextStyle.FULL, locale),
                                    selected = days and bit != 0,
                                    onToggle = { selected ->
                                        if (selected) days = days or bit
                                        else if ((days and Habit.EVERY_DAY_MASK).countOneBits() > 1) days = days and bit.inv()
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.habits_reminder),
                    subtitle = stringResource(R.string.habits_reminder_summary),
                    checked = reminderEnabled,
                    onCheckedChange = { reminderEnabled = it },
                    shape = RoundedCornerShape(20.dp),
                )
            }
            if (reminderEnabled) {
                item {
                    ValueRow(
                        title = stringResource(R.string.habits_reminder_time),
                        value = TimeText.format(LocalTime.ofSecondOfDay(reminderMinutes * 60L), is24),
                        onClick = { timeDialog = true },
                        shape = RoundedCornerShape(20.dp),
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        onSave(Habit(
                            id = habit?.id ?: 0,
                            name = name.trim(),
                            icon = icon.ifBlank { Habit.DEFAULT_ICON },
                            colorIndex = colour,
                            daysMask = days,
                            reminderMinutes = if (reminderEnabled) reminderMinutes else null,
                            createdEpochDay = habit?.createdEpochDay ?: today.toEpochDay(),
                            sortOrder = habit?.sortOrder ?: 0,
                        ))
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.habits_save)) }
            }
        }
    }
    if (timeDialog) {
        TimeEntryDialog(
            hour = reminderMinutes / 60,
            minute = reminderMinutes % 60,
            is24 = is24,
            onConfirm = { hour, minute -> reminderMinutes = hour * 60 + minute },
            onDismiss = { timeDialog = false },
        )
    }
}

private fun safeHabitText(value: String, max: Int): String {
    val cut = value.take(max)
    return if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
}

private val HabitIcons = listOf("✓", "📚", "🏃", "🧘", "💧", "🥗", "🦷", "✍️", "🎨", "🎸", "🌱", "😴")
