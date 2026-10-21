package com.riyadm.wakenudge.engine

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceCalendar(val id: Long, val name: String, val account: String, val color: Int)

/**
 * Reads all-day events from calendars the user picked (typically a "Holidays in …" calendar already synced to the
 * device) so alarms can skip those days. Entirely local: it only talks to the on-device Calendar Provider, and only
 * once the user has granted the optional READ_CALENDAR permission.
 */
@Singleton
class CalendarHolidayProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun availableCalendars(): List<DeviceCalendar> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
        )
        return runCatching {
            context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC")
                ?.use { c ->
                    buildList {
                        while (c.moveToNext()) add(DeviceCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getInt(3)))
                    }
                }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Epoch days covered by all-day events in [calendarIds] from [from] for [days] days. */
    fun holidayDays(calendarIds: Set<Long>, from: LocalDate, days: Int): Set<Long> {
        if (calendarIds.isEmpty() || !hasPermission()) return emptySet()
        // All-day instances are stored as UTC midnight-to-midnight, so query and decode in UTC.
        val begin = from.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val end = from.plusDays(days.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
        }.build()
        val projection = arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END)
        val selection = "${CalendarContract.Instances.ALL_DAY} = 1 AND ${CalendarContract.Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",")})"
        return runCatching {
            val result = HashSet<Long>()
            context.contentResolver.query(uri, projection, selection, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val start = Instant.ofEpochMilli(c.getLong(0)).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    val last = Instant.ofEpochMilli(c.getLong(1) - 1).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    for (d in start..maxOf(start, last)) result += d
                }
            }
            result
        }.getOrDefault(emptySet())
    }
}
