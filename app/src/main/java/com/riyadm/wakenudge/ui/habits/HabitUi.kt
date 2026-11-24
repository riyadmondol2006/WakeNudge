package com.riyadm.wakenudge.ui.habits

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.habits.Habit
import com.riyadm.wakenudge.domain.habits.HabitDayState
import com.riyadm.wakenudge.domain.habits.HabitRules
import com.riyadm.wakenudge.ui.util.currentLocale
import java.time.LocalDate
import java.time.format.TextStyle

val HabitPalette = listOf(
    Color(0xFF7C6AD8), Color(0xFFD36578), Color(0xFFDD8B3D), Color(0xFF4F9B78),
    Color(0xFF368FB5), Color(0xFF9365B8), Color(0xFF73863D), Color(0xFFB56345),
)

fun habitColour(index: Int): Color = HabitPalette[index.coerceIn(0, HabitPalette.lastIndex)]

@Composable
fun HabitFlame(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val path = Path().apply {
            moveTo(size.width * 0.56f, size.height * 0.03f)
            cubicTo(size.width * 0.62f, size.height * 0.28f, size.width * 0.91f, size.height * 0.35f, size.width * 0.88f, size.height * 0.62f)
            cubicTo(size.width * 0.85f, size.height * 0.88f, size.width * 0.65f, size.height * 0.98f, size.width * 0.45f, size.height * 0.96f)
            cubicTo(size.width * 0.18f, size.height * 0.93f, size.width * 0.06f, size.height * 0.73f, size.width * 0.14f, size.height * 0.49f)
            cubicTo(size.width * 0.19f, size.height * 0.34f, size.width * 0.31f, size.height * 0.25f, size.width * 0.36f, size.height * 0.16f)
            cubicTo(size.width * 0.39f, size.height * 0.35f, size.width * 0.48f, size.height * 0.36f, size.width * 0.56f, size.height * 0.03f)
            close()
        }
        drawPath(path, colour)
        val inner = Path().apply {
            moveTo(size.width * 0.52f, size.height * 0.48f)
            cubicTo(size.width * 0.59f, size.height * 0.63f, size.width * 0.72f, size.height * 0.67f, size.width * 0.67f, size.height * 0.80f)
            cubicTo(size.width * 0.62f, size.height * 0.94f, size.width * 0.37f, size.height * 0.94f, size.width * 0.34f, size.height * 0.78f)
            cubicTo(size.width * 0.31f, size.height * 0.66f, size.width * 0.46f, size.height * 0.61f, size.width * 0.52f, size.height * 0.48f)
            close()
        }
        drawPath(inner, colour.copy(alpha = 0.35f), blendMode = androidx.compose.ui.graphics.BlendMode.DstOut)
    }
}

@Composable
fun HabitStreakBadge(streak: Int, colour: Color, modifier: Modifier = Modifier) {
    val description = pluralStringResource(R.plurals.habits_current_streak, streak, streak)
    Row(modifier.clearAndSetSemantics { contentDescription = description }, verticalAlignment = Alignment.CenterVertically) {
        HabitFlame(colour)
        Text(
            streak.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = colour,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
fun HabitWeekRow(
    habit: Habit,
    weekStart: LocalDate,
    today: LocalDate,
    done: Set<Long>,
    onToggle: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    Row(modifier.fillMaxWidth()) {
        repeat(7) { index ->
            val date = weekStart.plusDays(index.toLong())
            val state = HabitRules.dayState(habit, date, today, done)
            val fullDate = date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", locale))
            val description = stringResource(when (state) {
                HabitDayState.DONE -> R.string.habits_day_done
                HabitDayState.OPEN -> R.string.habits_day_open
                HabitDayState.MISSED -> R.string.habits_day_missed
                HabitDayState.NOT_DUE -> R.string.habits_day_not_due
                HabitDayState.FUTURE -> R.string.habits_day_future
            }, fullDate)
            val editable = HabitRules.canToggle(habit, date, today)
            val colour = habitColour(habit.colorIndex)
            Column(
                Modifier
                    .weight(1f)
                    .defaultMinSize(minHeight = 64.dp)
                    .then(if (editable) Modifier.toggleable(
                        value = state == HabitDayState.DONE,
                        role = Role.Checkbox,
                        onValueChange = { onToggle(date) },
                    ) else Modifier)
                    .semantics { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .then(when (state) {
                            HabitDayState.DONE -> Modifier.background(colour)
                            HabitDayState.OPEN -> Modifier.border(2.dp, colour, CircleShape)
                            HabitDayState.MISSED -> Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            else -> Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f))
                        }),
                    contentAlignment = Alignment.Center,
                ) {
                    when (state) {
                        HabitDayState.DONE -> Icon(
                            Icons.Filled.Check,
                            null,
                            tint = if (colour.luminance() > 0.45f) Color.Black else Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                        HabitDayState.MISSED -> Box(Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
                        HabitDayState.OPEN -> Unit
                        HabitDayState.NOT_DUE, HabitDayState.FUTURE -> Text("·", color = MaterialTheme.colorScheme.outline)
                    }
                }
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
