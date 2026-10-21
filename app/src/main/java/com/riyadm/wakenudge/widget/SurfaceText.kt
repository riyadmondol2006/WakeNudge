package com.riyadm.wakenudge.widget

import android.content.Context
import android.text.format.DateFormat
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.core.TimeText
import com.riyadm.wakenudge.domain.model.TimeFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** The pieces of the next-alarm line, already localised; null parts are simply not drawn. */
internal data class NextAlarmTexts(
    val time: String?,
    val day: String?,
    val label: String?,
    /** The honest status line: why nothing is shown, or what is being skipped. */
    val note: String?,
) {
    /** "Tomorrow · Wake up" (or just the day when the alarm has no label). */
    fun dayAndLabel(context: Context): String? = when {
        day != null && label != null -> context.getString(R.string.widget_line_time_day, day, label)
        else -> day ?: label
    }
}

/** Turns the pure models of this package into the words shown on widgets and on the tile. */
internal class SurfaceText(
    private val context: Context,
    timeFormat: TimeFormat,
    private val locale: Locale = Locale.getDefault(),
) {
    private val is24 = TimeText.is24Hour(context, timeFormat)

    fun time(time: LocalTime): String = TimeText.format(time, is24, locale)

    fun day(day: RelativeDay): String = when (day) {
        RelativeDay.Today -> context.getString(R.string.widget_day_today)
        RelativeDay.Tomorrow -> context.getString(R.string.widget_day_tomorrow)
        is RelativeDay.Weekday -> day.day.getDisplayName(TextStyle.FULL, locale)
        is RelativeDay.OnDate -> date(day.date)
    }

    fun shortWeekday(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale)

    /** "Wed, 12 Nov" in the order and punctuation of the user's locale. */
    fun date(date: LocalDate): String = runCatching {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale).format(date)
    }.getOrElse { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date) }

    fun note(note: OutlookNote): String = when (note) {
        is OutlookNote.SkippingOnce -> context.getString(R.string.widget_note_skipping, date(note.date))
        is OutlookNote.DayOff -> context.getString(R.string.widget_note_day_off, date(note.date))
        is OutlookNote.Vacation -> note.resumeEpochDay
            ?.let { context.getString(R.string.widget_note_vacation, date(LocalDate.ofEpochDay(it))) }
            ?: context.getString(R.string.widget_note_vacation_indefinite)
        OutlookNote.NoAlarms -> context.getString(R.string.widget_status_no_alarms)
        OutlookNote.AllOff -> context.getString(R.string.widget_status_all_off)
        OutlookNote.NothingUpcoming -> context.getString(R.string.widget_status_nothing_upcoming)
    }

    fun repeat(summary: RepeatSummary): String = when (summary) {
        RepeatSummary.Once -> context.getString(R.string.widget_repeat_once)
        is RepeatSummary.OnDate -> date(summary.date)
        RepeatSummary.Daily -> context.getString(R.string.widget_repeat_daily)
        RepeatSummary.Weekdays -> context.getString(R.string.widget_repeat_weekdays)
        RepeatSummary.Weekends -> context.getString(R.string.widget_repeat_weekends)
        is RepeatSummary.Days -> summary.days.joinToString(", ") { shortWeekday(it) }
    }

    fun describe(outlook: AlarmOutlook, today: LocalDate): NextAlarmTexts {
        val next = outlook.next
        val note = outlook.note?.let(::note)
        if (next == null) return NextAlarmTexts(time = null, day = null, label = null, note = note)
        return NextAlarmTexts(
            time = time(next.at.toLocalTime()),
            day = day(RelativeDay.of(today, next.at.toLocalDate())),
            label = next.alarm.label.ifBlank { null },
            note = note,
        )
    }
}
