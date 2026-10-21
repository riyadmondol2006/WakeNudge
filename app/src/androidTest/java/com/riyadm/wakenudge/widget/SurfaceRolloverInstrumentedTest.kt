package com.riyadm.wakenudge.widget

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The single midnight alarm that replaces the widgets' periodic update, against the real AlarmManager. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class SurfaceRolloverInstrumentedTest {
    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var rollover: SurfaceRollover

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() = hilt.inject()

    @After fun tearDown() = rollover.sync(false)

    /** The registered alarm's PendingIntent exists exactly while the alarm is armed (cancelling destroys it). */
    private fun armed(): Boolean = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, SurfaceEventsReceiver::class.java).setAction(SurfaceEventsReceiver.ACTION_ROLLOVER),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    ) != null

    @Test fun armedWhileAnyWidgetExistsAndDroppedWhenNoneDoes() {
        rollover.sync(false)
        assertFalse(armed())
        rollover.sync(true)
        assertTrue(armed())
        rollover.sync(true)
        assertTrue("arming again replaces, it does not stack or fail", armed())
        rollover.sync(false)
        assertFalse(armed())
    }

    @Test fun droppingWithNothingArmedIsHarmless() {
        rollover.sync(false)
        rollover.sync(false)
        assertFalse(armed())
    }
}
