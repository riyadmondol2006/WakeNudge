package com.riyadm.wakenudge.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.TimeFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** Human wording for schedules and countdowns. Everything user-visible comes from string resources. */
object Formatters {

    /** "In 7 h 32 min", "In 12 min", "In 2 d 3 h". Never says "0 min": rounds up so it reads true when it rings. */
    fun untilText(context: Context, fromMillis: Long, toMillis: Long): String {
        val minutes = Duration.ofMillis((toMillis - fromMillis).coerceAtLeast(0)).let { (it.toMinutes() + if (it.toMillis() % 60_000 > 0) 1 else 0) }
        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val mins = minutes % 60
        return when {
            minutes < 1 -> context.getString(R.string.until_less_than_minute)
            days > 0 -> context.getString(R.string.until_days_hours, days, hours)
            hours > 0 -> context.getString(R.string.until_hours_minutes, hours, mins)
            else -> context.getString(R.string.until_minutes, mins)
        }
    }

    /** Short summary of when an alarm repeats, ordered from the user's first day of the week. */
    fun repeatSummary(context: Context, alarm: Alarm, firstDay: DayOfWeek, locale: Locale = Locale.getDefault()): String {
        val mask = alarm.repeatDaysMask
        return when {
            mask == 0 -> alarm.oneTimeDate?.let { dateShort(it, locale) } ?: context.getString(R.string.repeat_once)
            mask == Alarm.EVERY_DAY_MASK -> context.getString(R.string.repeat_every_day)
            mask == Alarm.WEEKDAYS_MASK -> context.getString(R.string.repeat_weekdays)
            mask == Alarm.WEEKEND_MASK -> context.getString(R.string.repeat_weekends)
            else -> orderedDays(firstDay).filter { alarm.repeatsOn(it) }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
        }
    }

    fun orderedDays(firstDay: DayOfWeek): List<DayOfWeek> = (0 until 7).map { firstDay.plus(it.toLong()) }

    fun dateShort(date: LocalDate, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)

    fun dayAndDate(at: ZonedDateTime, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(at)

    fun fullDate(at: ZonedDateTime, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(at)

    /** "Today" / "Tomorrow" / "Mon" relative to [now], falling back to a date for far-off rings. */
    fun relativeDay(context: Context, target: ZonedDateTime, now: ZonedDateTime, locale: Locale = Locale.getDefault()): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), target.toLocalDate())
        return when {
            days <= 0L -> context.getString(R.string.day_today)
            days == 1L -> context.getString(R.string.day_tomorrow)
            days < 7L -> target.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else -> dayAndDate(target, locale)
        }
    }

    /** City label from an IANA id: "America/Port_of_Spain" -> "Port of Spain". */
    fun cityName(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

    fun regionName(zoneId: String): String = if ('/' in zoneId) zoneId.substringBefore('/').replace('_', ' ') else ""

    /** "+5 h", "-3 h 30 min", "same time" difference between two zones at the same instant. */
    fun offsetDifference(context: Context, from: ZoneId, to: ZoneId, at: java.time.Instant = java.time.Instant.now()): String {
        val diff = (to.rules.getOffset(at).totalSeconds - from.rules.getOffset(at).totalSeconds) / 60
        if (diff == 0) return context.getString(R.string.clock_same_time)
        val sign = if (diff > 0) "+" else "−"
        val abs = kotlin.math.abs(diff)
        val h = abs / 60
        val m = abs % 60
        return if (m == 0) context.getString(R.string.clock_offset_h, sign, h) else context.getString(R.string.clock_offset_hm, sign, h, m)
    }
}

@Composable
fun rememberIs24Hour(preference: TimeFormat): Boolean {
    val context = LocalContext.current
    return remember(preference, context) { com.riyadm.wakenudge.core.TimeText.is24Hour(context, preference) }
}

/** The user's current locale, observed so formatted text updates when the language changes. */
@androidx.compose.runtime.Composable
@androidx.compose.runtime.ReadOnlyComposable
fun currentLocale(): Locale = androidx.compose.ui.text.intl.Locale.current.platformLocale
