package com.riyadm.wakenudge.ui

import com.riyadm.wakenudge.ui.protection.OverlayStage
import com.riyadm.wakenudge.ui.protection.ProtectionInputs
import com.riyadm.wakenudge.ui.protection.ProtectionPolicy
import com.riyadm.wakenudge.ui.protection.StepId
import com.riyadm.wakenudge.ui.protection.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Protection setup over every combination of what Android can report. */
class ProtectionPolicyTest {

    /** Every combination of the boolean inputs, at both sides of Android 13, at every overlay stage. */
    private val all: List<ProtectionInputs> = buildList {
        for (bits in 0 until (1 shl 8)) for (sdk in listOf(30, 37)) for (stage in OverlayStage.entries) {
            fun b(i: Int) = bits and (1 shl i) != 0
            add(
                ProtectionInputs(
                    sdkInt = sdk, overlayGranted = b(0), overlayStage = stage, notificationsOk = b(1), fullScreenOk = b(2),
                    batteryExempt = b(3), anyRehearsalFinished = b(4), adminActive = b(5), ringingOrSnoozed = b(6),
                ),
            )
        }
    }

    private val full = ProtectionInputs(
        sdkInt = 37, overlayGranted = true, notificationsOk = true, fullScreenOk = true, batteryExempt = true,
        anyRehearsalFinished = true, adminActive = true, ringingOrSnoozed = false,
    )

    @Test fun `the readiness line counts exactly the steps not done, for every combination`() {
        for (i in all) {
            val expected = listOf(i.overlayGranted, i.batteryExempt, i.notificationsOk, i.fullScreenOk, i.anyRehearsalFinished, i.adminActive).count { !it }
            assertEquals(i.toString(), expected, ProtectionPolicy.stepsLeft(i))
        }
        assertEquals("fully protected", 0, ProtectionPolicy.stepsLeft(full))
        assertEquals(2, ProtectionPolicy.stepsLeft(full.copy(overlayGranted = false, adminActive = false)))
    }

    @Test fun `device admin stays locked until one rehearsal has finished`() {
        for (i in all.filter { !it.anyRehearsalFinished && !it.adminActive }) {
            assertEquals(StepState.LOCKED, ProtectionPolicy.steps(i).single { it.id == StepId.ADMIN }.state)
            assertFalse(ProtectionPolicy.admin(i).canTurnOn)
        }
        for (i in all.filter { it.anyRehearsalFinished && !it.adminActive }) {
            assertEquals(StepState.TODO, ProtectionPolicy.steps(i).single { it.id == StepId.ADMIN }.state)
            assertTrue(ProtectionPolicy.admin(i).canTurnOn)
        }
    }

    @Test fun `no state hides the Turn off row, and only a ring or snooze refuses it`() {
        for (i in all) {
            val row = ProtectionPolicy.admin(i)
            // The row is a value, never absent; when admin is on it always says so.
            assertEquals(i.toString(), i.adminActive, row.active)
            if (i.adminActive) assertEquals(i.toString(), !i.ringingOrSnoozed, row.canTurnOff)
            assertTrue("every step list has the admin step: $i", ProtectionPolicy.steps(i).any { it.id == StepId.ADMIN })
        }
    }

    @Test fun `the restricted settings help shows only after the setting was tried, on Android 13 and later, without the grant`() {
        for (i in all) {
            val expected = !i.overlayGranted && i.sdkInt >= 33 && i.overlayStage != OverlayStage.NOT_TRIED
            assertEquals(i.toString(), expected, ProtectionPolicy.showRestrictedHelp(i))
        }
    }

    @Test fun `there is no Power Guard step`() {
        assertEquals(
            listOf(StepId.OVERLAY, StepId.BATTERY, StepId.NOTIFICATIONS, StepId.FULL_SCREEN, StepId.REHEARSAL, StepId.ADMIN),
            ProtectionPolicy.steps(full).map { it.id },
        )
    }
}
