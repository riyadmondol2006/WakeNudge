package com.riyadm.wakenudge.widget

import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.TimeFormat
import com.riyadm.wakenudge.domain.scheduling.NextAlarm
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator
import com.riyadm.wakenudge.domain.scheduling.NextTriggerCalculator.Suppression
import com.riyadm.wakenudge.domain.scheduling.SchedulingContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLogicTest {

    private val zone = ZoneId.of("UTC")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)
    private fun next(day: Int, hour: Int, id: Long = 1) = NextAlarm(Alarm(id = id, hour = hour), at(day, hour))
    private fun epoch(day: Int) = LocalDate.of(2026, 10, day).toEpochDay()

    // ---- the honest status line --------------------------------------------------------------------------------------

    @Test fun `with an alarm and nothing unusual there is no note`() {
        val outlook = AlarmOutlook.decide(next(22, 7), emptyList(), totalAlarms = 2, enabledAlarms = 2)
        assertEquals(next(22, 7), outlook.next)
        assertNull(outlook.note)
    }

    @Test fun `no alarms at all, all switched off and nothing upcoming are told apart`() {
        assertEquals(OutlookNote.NoAlarms, AlarmOutlook.decide(null, emptyList(), 0, 0).note)
        assertEquals(OutlookNote.AllOff, AlarmOutlook.decide(null, emptyList(), 3, 0).note)
        assertEquals(OutlookNote.NothingUpcoming, AlarmOutlook.decide(null, emptyList(), 3, 2).note)
    }

    @Test fun `vacation mode explains why nothing will ring and when alarms resume`() {
        val suppressed = listOf(SuppressedOccurrence(LocalTime.of(7, 0), Suppression.Vacation(epoch(30))))
        assertEquals(OutlookNote.Vacation(epoch(30)), AlarmOutlook.decide(null, suppressed, 1, 1).note)
        val indefinite = listOf(SuppressedOccurrence(LocalTime.of(7, 0), Suppression.Vacation(null)))
        assertEquals(OutlookNote.Vacation(null), AlarmOutlook.decide(null, indefinite, 1, 1).note)
    }

    @Test fun `an alarm that ignores vacation still rings and the note says vacation is on`() {
        val suppressed = listOf(SuppressedOccurrence(LocalTime.of(7, 0), Suppression.Vacation(null)))
        val outlook = AlarmOutlook.decide(next(22, 9), suppressed, totalAlarms = 2, enabledAlarms = 2)
        assertEquals(next(22, 9), outlook.next)
        assertEquals(OutlookNote.Vacation(null), outlook.note)
    }

    @Test fun `a skipped occurrence before the next ring is reported`() {
        val suppressed = listOf(SuppressedOccurrence(LocalTime.of(7, 0), Suppression.SkippedOnce(LocalDate.of(2026, 10, 22))))
        val outlook = AlarmOutlook.decide(next(23, 7), suppressed, 1, 1)
        assertEquals(OutlookNote.SkippingOnce(LocalDate.of(2026, 10, 22)), outlook.note)
    }

    @Test fun `the skip note names the skipped alarm, so the widget can undo that skip`() {
        val suppressed = listOf(SuppressedOccurrence(LocalTime.of(7, 0), Suppression.SkippedOnce(LocalDate.of(2026, 10, 22)), alarmId = 42))
        val outlook = AlarmOutlook.decide(next(23, 7), suppressed, 1, 1)
        assertEquals(42L, (outlook.note as OutlookNote.SkippingOnce).alarmId)
    }

    @Test fun `turning a one-time alarm off from the widget takes a second tap within a few seconds`() {
        TurnOffConfirmation.reset()
        assertEquals("first tap only arms it", false, TurnOffConfirmation.confirm(5, 1_000))
        assertEquals("second tap goes through", true, TurnOffConfirmation.confirm(5, 3_000))
        assertEquals("and the next first tap arms it again", false, TurnOffConfirmation.confirm(5, 4_000))
        assertEquals("a tap long after the first starts over", false, TurnOffConfirmation.confirm(5, 4_000 + TurnOffConfirmation.WINDOW_MS + 1))
        assertEquals("another alarm needs its own taps", false, TurnOffConfirmation.confirm(6, 13_000))
    }

    @Test fun `a skipped occurrence after the next ring is not worth mentioning yet`() {
        val suppressed = listOf(SuppressedOccurrence(LocalTime.of(7, 0), Suppression.SkippedOnce(LocalDate.of(2026, 10, 25))))
        assertNull(AlarmOutlook.decide(next(22, 7), suppressed, 2, 2).note)
    }

    @Test fun `a holiday is reported as a day off`() {
        val suppressed = listOf(SuppressedOccurrence(LocalTime.of(6, 0), Suppression.Exception(LocalDate.of(2026, 10, 22))))
        assertEquals(OutlookNote.DayOff(LocalDate.of(2026, 10, 22)), AlarmOutlook.decide(next(23, 6), suppressed, 1, 1).note)
    }

    @Test fun `the earliest suppressed occurrence wins`() {
        val suppressed = listOf(
            SuppressedOccurrence(LocalTime.of(7, 0), Suppression.SkippedOnce(LocalDate.of(2026, 10, 24))),
            SuppressedOccurrence(LocalTime.of(8, 0), Suppression.Exception(LocalDate.of(2026, 10, 22))),
        )
        assertEquals(OutlookNote.DayOff(LocalDate.of(2026, 10, 22)), AlarmOutlook.decide(next(26, 7), suppressed, 2, 2).note)
    }

    @Test fun `the outlook agrees with the real calculator for a skipped alarm`() {
        val calculator = NextTriggerCalculator()
        val now = at(21, 12)
        val alarm = Alarm(id = 1, hour = 7, repeatDaysMask = Alarm.EVERY_DAY_MASK, skipEpochDay = epoch(22))
        val suppression = calculator.suppressionOfNaturalNext(alarm, now, SchedulingContext())!!
        val ringsNext = calculator.next(alarm, now, SchedulingContext())!!
        val outlook = AlarmOutlook.decide(NextAlarm(alarm, ringsNext), listOf(SuppressedOccurrence(alarm.time, suppression)), 1, 1)
        assertEquals(at(23, 7), outlook.next!!.at)
        assertEquals(OutlookNote.SkippingOnce(LocalDate.of(2026, 10, 22)), outlook.note)
    }

    // ---- relative days -----------------------------------------------------------------------------------------------

    @Test fun `days are named relative to today`() {
        val today = LocalDate.of(2026, 10, 21) // a Wednesday
        assertEquals(RelativeDay.Today, RelativeDay.of(today, today))
        assertEquals(RelativeDay.Tomorrow, RelativeDay.of(today, today.plusDays(1)))
        assertEquals(RelativeDay.Weekday(DayOfWeek.FRIDAY), RelativeDay.of(today, today.plusDays(2)))
        assertEquals(RelativeDay.Weekday(DayOfWeek.TUESDAY), RelativeDay.of(today, today.plusDays(6)))
        assertEquals(RelativeDay.OnDate(today.plusDays(7)), RelativeDay.of(today, today.plusDays(7)))
        assertEquals(RelativeDay.OnDate(today.minusDays(1)), RelativeDay.of(today, today.minusDays(1)))
    }

    // ---- repeat summaries --------------------------------------------------------------------------------------------

    @Test fun `repeat patterns are summarised`() {
        fun summary(mask: Int, first: DayOfWeek = DayOfWeek.MONDAY, date: Long? = null) =
            RepeatSummary.of(Alarm(repeatDaysMask = mask, oneTimeEpochDay = date), first)
        assertEquals(RepeatSummary.Once, summary(0))
        assertEquals(RepeatSummary.OnDate(LocalDate.ofEpochDay(20_500)), summary(0, date = 20_500))
        assertEquals(RepeatSummary.Daily, summary(Alarm.EVERY_DAY_MASK))
        assertEquals(RepeatSummary.Weekdays, summary(Alarm.WEEKDAYS_MASK))
        assertEquals(RepeatSummary.Weekends, summary(Alarm.WEEKEND_MASK))
        assertEquals(
            RepeatSummary.Days(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.SUNDAY)),
            summary(Alarm.maskOf(listOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY))),
        )
    }

    @Test fun `individual days start from the first day of the week`() {
        val mask = Alarm.maskOf(listOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY.let { DayOfWeek.THURSDAY }))
        val sundayFirst = RepeatSummary.of(Alarm(repeatDaysMask = mask), DayOfWeek.SATURDAY) as RepeatSummary.Days
        assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.MONDAY, DayOfWeek.THURSDAY), sundayFirst.days)
    }

    // ---- clock formats -----------------------------------------------------------------------------------------------

    @Test fun `the app's time format overrides the system one for the clock face`() {
        val system = ClockFormats.of(TimeFormat.SYSTEM)
        assertEquals("h:mm", system.time12)
        assertEquals("HH:mm", system.time24)
        assertEquals("", system.marker24)

        val twelve = ClockFormats.of(TimeFormat.H12)
        assertEquals(twelve.time12, twelve.time24)
        assertEquals("a", twelve.marker24)

        val twentyFour = ClockFormats.of(TimeFormat.H24)
        assertEquals("HH:mm", twentyFour.time12)
        assertEquals("", twentyFour.marker12)
        assertTrue(twentyFour.marker24.isEmpty())
    }
}
