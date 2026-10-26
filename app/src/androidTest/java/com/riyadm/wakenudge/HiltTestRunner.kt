package com.riyadm.wakenudge

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import com.riyadm.wakenudge.engine.PendingAlarms
import com.riyadm.wakenudge.engine.TriggerKind
import dagger.hilt.android.testing.HiltTestApplication
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/** Instrumentation runner that swaps in Hilt's test application. */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, name: String?, context: Context?): Application =
        super.newApplication(cl, HiltTestApplication::class.java.name, context)

    override fun onCreate(arguments: Bundle) {
        val listeners = listOfNotNull(arguments.getString("listener"), GlobalTriggerCleanup::class.java.name)
        arguments.putString("listener", listeners.joinToString(","))
        super.onCreate(arguments)
    }
}

/**
 * Cancels the app-wide triggers (the watchdog, the bedtime reminder, the low-battery check) after every test. A test
 * that arms an alarm also arms the battery check, which fires a minute later; landing in a later test without Hilt's
 * component, its receiver would crash the whole run.
 */
class GlobalTriggerCleanup : RunListener() {
    override fun testFinished(description: Description) {
        val pending = PendingAlarms(InstrumentationRegistry.getInstrumentation().targetContext)
        for (kind in listOf(TriggerKind.BATTERY_CHECK, TriggerKind.WATCHDOG, TriggerKind.BEDTIME)) runCatching { pending.cancel(kind, 0) }
    }
}
