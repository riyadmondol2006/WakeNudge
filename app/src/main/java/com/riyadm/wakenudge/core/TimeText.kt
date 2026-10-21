package com.riyadm.wakenudge.core

import android.content.Context
import android.text.format.DateFormat
import com.riyadm.wakenudge.domain.model.TimeFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formats clock times the way the user asked for (system, 12 h or 24 h) in the current locale. */
object TimeText {
    fun is24Hour(context: Context, preference: TimeFormat): Boolean = when (preference) {
        TimeFormat.H24 -> true
        TimeFormat.H12 -> false
        TimeFormat.SYSTEM -> DateFormat.is24HourFormat(context)
    }

    fun format(time: LocalTime, is24: Boolean, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", locale).format(time)

    fun format(hour: Int, minute: Int, is24: Boolean, locale: Locale = Locale.getDefault()): String =
        format(LocalTime.of(hour, minute), is24, locale)

    /** Spoken form for TTS, e.g. "7:30 AM" / "19:30". */
    fun spoken(time: LocalTime, is24: Boolean, locale: Locale = Locale.getDefault()): String = format(time, is24, locale)

    /** "1 h 05 min", "12 min", "45 s" for countdown-like labels. */
    fun duration(totalMillis: Long): String {
        val totalSeconds = (totalMillis / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "%d h %02d min".format(h, m)
            m > 0 -> "%d min".format(m)
            else -> "%d s".format(s)
        }
    }

    /** "00:05:09" / "05:09" clock style used by timers and the stopwatch. */
    fun clockDuration(millis: Long, withHundredths: Boolean = false): String {
        val total = millis.coerceAtLeast(0)
        val h = total / 3_600_000
        val m = (total / 60_000) % 60
        val s = (total / 1000) % 60
        val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        return if (withHundredths) base + ".%02d".format((total % 1000) / 10) else base
    }
}
