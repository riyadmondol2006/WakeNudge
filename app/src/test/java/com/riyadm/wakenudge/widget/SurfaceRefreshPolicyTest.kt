package com.riyadm.wakenudge.widget

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.model.StopwatchState
import com.riyadm.wakenudge.domain.model.TimeFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceRefreshPolicyTest {

    // ---- the single midnight alarm that replaces the platform's periodic widget update ----------------------------

    private val utc = ZoneId.of("UTC")

    @Test fun `the rollover is just after the next local midnight`() {
        val now = ZonedDateTime.of(2026, 10, 21, 16, 26, 30, 0, utc)
        assertEquals(ZonedDateTime.of(2026, 10, 22, 0, 0, 2, 0, utc), SurfaceRollover.nextRollover(now))
    }

    @Test fun `the rollover from just before midnight is the coming midnight, not the one after`() {
        val now = ZonedDateTime.of(2026, 10, 21, 23, 59, 59, 0, utc)
        assertEquals(ZonedDateTime.of(2026, 10, 22, 0, 0, 2, 0, utc), SurfaceRollover.nextRollover(now))
    }

    @Test fun `exactly at midnight the rollover is a day away`() {
        val now = ZonedDateTime.of(2026, 10, 22, 0, 0, 0, 0, utc)
        assertEquals(ZonedDateTime.of(2026, 10, 23, 0, 0, 2, 0, utc), SurfaceRollover.nextRollover(now))
    }

    @Test fun `the rollover follows the zone the phone is in, not UTC`() {
        val dhaka = ZoneId.of("Asia/Dhaka")
        val now = ZonedDateTime.of(2026, 10, 21, 22, 0, 0, 0, dhaka)
        assertEquals(ZonedDateTime.of(2026, 10, 22, 0, 0, 2, 0, dhaka), SurfaceRollover.nextRollover(now))
    }

    @Test fun `a day shorter or longer because of daylight saving still ends at local midnight`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 25 hours long: clocks go back on 2026-10-25, so one o'clock to the next midnight is 24 real hours.
        val longDay = ZonedDateTime.of(2026, 10, 25, 1, 0, 0, 0, berlin)
        val longEnd = SurfaceRollover.nextRollover(longDay)
        assertEquals(LocalDateTime.of(2026, 10, 26, 0, 0, 2), longEnd.toLocalDateTime())
        assertEquals(24L, Duration.between(longDay, longEnd.minusSeconds(2)).toHours())
        // 23 hours long: clocks go forward on 2026-03-29, so one o'clock to the next midnight is 22 real hours.
        val shortDay = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, berlin)
        val shortEnd = SurfaceRollover.nextRollover(shortDay)
        assertEquals(LocalDateTime.of(2026, 3, 30, 0, 0, 2), shortEnd.toLocalDateTime())
        assertEquals(22L, Duration.between(shortDay, shortEnd.minusSeconds(2)).toHours())
    }

    @Test fun `when midnight does not exist the rollover lands on the first moment of the day`() {
        // Some zones skip 00:00 on the day clocks go forward (Havana: 00:00 -> 01:00 on 2026-03-08).
        val havana = ZoneId.of("America/Havana")
        val now = ZonedDateTime.of(2026, 3, 7, 20, 0, 0, 0, havana)
        val next = SurfaceRollover.nextRollover(now)
        assertEquals(8, next.dayOfMonth)
        assertTrue(next.isAfter(now))
        assertEquals(1, next.hour)
    }

    // ---- the "has this widget already been given this picture?" ledger ----------------------------------------------

    private val hour = 60 * 60_000L

    @Test fun `a picture that was never recorded is not current`() {
        assertFalse(RenderLedger(hour).isCurrent("w", "picture", 0))
    }

    @Test fun `the same picture is current, a different one is not`() {
        val ledger = RenderLedger(hour)
        ledger.record("w", "a", 1_000)
        assertTrue(ledger.isCurrent("w", "a", 2_000))
        assertFalse(ledger.isCurrent("w", "b", 2_000))
    }

    @Test fun `widgets are tracked independently`() {
        val ledger = RenderLedger(hour)
        ledger.record("one", "a", 0)
        assertFalse(ledger.isCurrent("two", "a", 0))
    }

    @Test fun `a picture is only trusted for the maximum age, so a failed redraw heals`() {
        val ledger = RenderLedger(hour)
        ledger.record("w", "a", 0)
        assertTrue(ledger.isCurrent("w", "a", hour))
        assertFalse(ledger.isCurrent("w", "a", hour + 1))
    }

    @Test fun `a clock set back is never trusted`() {
        val ledger = RenderLedger(hour)
        ledger.record("w", "a", 10_000)
        assertFalse(ledger.isCurrent("w", "a", 9_000))
    }

    @Test fun `forgetting a widget makes its next picture new again`() {
        val ledger = RenderLedger(hour)
        ledger.record("w", "a", 0)
        ledger.forget("w")
        assertFalse(ledger.isCurrent("w", "a", 1))
    }

    @Test fun `recording replaces the previous picture`() {
        val ledger = RenderLedger(hour)
        ledger.record("w", "a", 0)
        ledger.record("w", "b", 5)
        assertFalse(ledger.isCurrent("w", "a", 6))
        assertTrue(ledger.isCurrent("w", "b", 6))
    }

    // ---- what makes a widget's picture change -------------------------------------------------------------------------

    private val setting = RenderSetting(Locale.US, is24Hour = false, zone = utc)
    private val today = LocalDate.of(2026, 10, 21)
    private val alarm = Alarm(id = 1, hour = 7, minute = 30, label = "Work")
    private val model = WidgetModel(
        settings = AppSettings(),
        today = today,
        firstDay = DayOfWeek.MONDAY,
        outlook = AlarmOutlook(next = null, note = OutlookNote.AllOff),
        alarms = listOf(alarm),
        groupsOff = emptySet(),
    )

    private fun nextAlarmPicture(m: WidgetModel, s: RenderSetting = setting, ids: Set<Int> = setOf(1)) =
        Picture.of(m, drawsOutlook = true, drawsAlarms = false, setting = s, widgetIds = ids)

    private fun listPicture(m: WidgetModel, s: RenderSetting = setting, ids: Set<Int> = setOf(1)) =
        Picture.of(m, drawsOutlook = false, drawsAlarms = true, setting = s, widgetIds = ids)

    @Test fun `unrelated settings such as a stopwatch lap do not redraw any widget`() {
        val lapped = model.copy(settings = model.settings.copy(stopwatch = StopwatchState(running = true, startedAtMillis = 5, laps = listOf(1, 2))))
        assertEquals(nextAlarmPicture(model), nextAlarmPicture(lapped))
        assertEquals(listPicture(model), listPicture(lapped))
    }

    @Test fun `the next-alarm widget ignores the alarm list and the list ignores the outlook`() {
        val edited = model.copy(alarms = listOf(alarm.copy(label = "Gym")))
        assertEquals(nextAlarmPicture(model), nextAlarmPicture(edited))
        assertFalse(listPicture(model) == listPicture(edited))

        val newOutlook = model.copy(outlook = AlarmOutlook(next = null, note = OutlookNote.NoAlarms))
        assertEquals(listPicture(model), listPicture(newOutlook))
        assertFalse(nextAlarmPicture(model) == nextAlarmPicture(newOutlook))
    }

    @Test fun `a new day, time format, colour mode, language, clock style, zone or widget redraws`() {
        val base = nextAlarmPicture(model)
        assertFalse(base == nextAlarmPicture(model.copy(today = today.plusDays(1))))
        assertFalse(base == nextAlarmPicture(model.copy(settings = model.settings.copy(timeFormat = TimeFormat.H24))))
        assertFalse(base == nextAlarmPicture(model.copy(settings = model.settings.copy(dynamicColor = false))))
        assertFalse(base == nextAlarmPicture(model, setting.copy(locale = Locale.GERMANY)))
        assertFalse(base == nextAlarmPicture(model, setting.copy(is24Hour = true)))
        assertFalse(base == nextAlarmPicture(model, setting.copy(zone = ZoneId.of("Asia/Dhaka"))))
        assertFalse(base == nextAlarmPicture(model, ids = setOf(1, 2)))
    }

    @Test fun `a second identical look-up is the same picture`() {
        assertEquals(nextAlarmPicture(model), nextAlarmPicture(model.copy()))
        assertEquals(listPicture(model), listPicture(model.copy(alarms = listOf(alarm.copy()))))
    }
}
