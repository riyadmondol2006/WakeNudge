package com.riyadm.wakenudge.tile

import com.riyadm.wakenudge.domain.model.VacationMode
import org.junit.Assert.assertEquals
import org.junit.Test

class VacationToggleTest {

    private val today = 20_000L

    @Test fun `switching on keeps a resume day in the future`() {
        assertEquals(VacationMode(true, 20_010), VacationMode(false, 20_010).toggled(today))
    }

    @Test fun `switching on without a resume day stays indefinite`() {
        assertEquals(VacationMode(true, null), VacationMode(false, null).toggled(today))
    }

    @Test fun `a resume day that has passed is cleared when switching on so it cannot be a silent no-op`() {
        assertEquals(VacationMode(true, null), VacationMode(false, 19_990).toggled(today))
        assertEquals("today counts as passed: alarms would resume immediately", VacationMode(true, null), VacationMode(false, today).toggled(today))
    }

    @Test fun `switching off preserves the resume day for next time`() {
        assertEquals(VacationMode(false, 20_010), VacationMode(true, 20_010).toggled(today))
        // A vacation past its resume day is already over (the tile shows alarms as on): a tap pauses again, until switched off.
        assertEquals(VacationMode(true, null), VacationMode(true, 19_990).toggled(today))
    }
}
