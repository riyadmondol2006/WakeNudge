package com.riyadm.wakenudge.update

import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.data.backup.SilentLog
import com.riyadm.wakenudge.domain.model.Alarm
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.engine.RingKind
import com.riyadm.wakenudge.engine.RingPhase
import com.riyadm.wakenudge.engine.RingSession
import com.riyadm.wakenudge.engine.RingSessionStore
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSettings(initial: AppSettings) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun current(): AppSettings = state.value
    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
    override suspend fun replace(settings: AppSettings) {
        state.value = settings
    }
}

private class MutableTime(var millis: Long) : TimeSource {
    override fun nowMillis(): Long = millis
    override fun zone(): ZoneId = ZoneId.of("UTC")
}

private class ScriptedClient(var next: () -> LookupResult) : ReleaseClient {
    var calls = 0
    override suspend fun latest(): LookupResult {
        calls++
        return next()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateManagerTest {
    private val repo = "https://github.com/riyadmondol2006/WakeNudge"
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val start = 1_800_000_000_000L

    private fun release(version: String) = ReleaseInfo(AppVersion.parse(version)!!, "v$version", "$repo/releases/tag/v$version", "Notes for $version")

    private class Rig(
        val manager: UpdateManager,
        val client: ScriptedClient,
        val settings: FakeSettings,
        val time: MutableTime,
        val ring: RingSessionStore,
        val log: SilentLog,
    )

    private fun TestScope.rig(
        installed: String = "1.0.5",
        autoCheck: Boolean = true,
        settings: AppSettings = AppSettings(onboardingDone = true),
        answer: () -> LookupResult = { LookupResult.Found(release("1.0.6")) },
    ): Rig {
        val client = ScriptedClient(answer)
        val store = FakeSettings(settings)
        val time = MutableTime(start)
        val ring = RingSessionStore()
        val log = SilentLog()
        val manager = UpdateManager(
            client, store, time, log, ring,
            UpdateEnvironment(installed, autoCheck, repo),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        return Rig(manager, client, store, time, ring, log)
    }

    private fun ringing() = RingSession(Alarm(label = "Gym"), RingKind.PRIMARY, RingPhase.RINGING, eventId = 1, scheduledAt = 0, startedAt = 0)

    // ---- when a check happens --------------------------------------------------------------------------

    @Test fun `the first check on a fresh install is due`() = runTest {
        val r = rig()
        r.manager.checkIfDue()
        assertEquals(1, r.client.calls)
        assertEquals(start, r.settings.state.value.lastUpdateCheckAt)
    }

    @Test fun `no second check within 24 hours and one once a day has passed`() = runTest {
        val r = rig()
        r.manager.checkIfDue()
        r.time.millis = start + day - 1
        r.manager.checkIfDue()
        assertEquals("23h59m is too soon", 1, r.client.calls)
        r.time.millis = start + day
        r.manager.checkIfDue()
        assertEquals("exactly a day later is due", 2, r.client.calls)
        assertEquals(start + day, r.settings.state.value.lastUpdateCheckAt)
    }

    @Test fun `a last check in the future because the clock moved back counts as due`() = runTest {
        val r = rig(settings = AppSettings(onboardingDone = true, lastUpdateCheckAt = start + 400 * day))
        r.manager.checkIfDue()
        assertEquals(1, r.client.calls)
        assertEquals(start, r.settings.state.value.lastUpdateCheckAt)
    }

    @Test fun `the due rule is a pure function of the two clocks`() {
        assertTrue(UpdateManager.isDue(0, start))
        assertTrue(UpdateManager.isDue(-5, start))
        assertTrue(!UpdateManager.isDue(start, start))
        assertTrue(!UpdateManager.isDue(start - day + 1, start))
        assertTrue(UpdateManager.isDue(start - day, start))
        assertTrue(UpdateManager.isDue(start + 1, start))
    }

    @Test fun `the switch turns automatic checks off`() = runTest {
        val r = rig(settings = AppSettings(onboardingDone = true, updateChecksEnabled = false))
        r.manager.checkIfDue()
        assertEquals(0, r.client.calls)
        assertEquals(UpdateStatus.Idle, r.manager.status.value)
    }

    @Test fun `nothing goes online before onboarding is finished`() = runTest {
        val r = rig(settings = AppSettings(onboardingDone = false))
        r.manager.checkIfDue()
        assertEquals(0, r.client.calls)
    }

    @Test fun `debug builds do not check on start`() = runTest {
        val r = rig(autoCheck = false)
        r.manager.checkIfDue()
        assertEquals(0, r.client.calls)
        assertNull(r.manager.prompt.value)
    }

    @Test fun `Check now works in a debug build with the switch off and ignores the 24 hour wait`() = runTest {
        val r = rig(autoCheck = false, settings = AppSettings(onboardingDone = true, updateChecksEnabled = false, lastUpdateCheckAt = start))
        r.manager.checkNow()
        assertEquals(1, r.client.calls)
        assertEquals(UpdateStatus.Available(release("1.0.6")), r.manager.status.value)
    }

    @Test fun `nothing is checked while an alarm rings`() = runTest {
        val r = rig()
        r.ring.set(ringing())
        r.manager.checkIfDue()
        r.manager.checkNow()
        assertEquals(0, r.client.calls)
        assertEquals(UpdateStatus.Failed(FailureKind.ALARM_RINGING), r.manager.status.value)
        assertNull(r.manager.prompt.value)
        assertTrue(r.log.entries.any { "alarm is ringing" in it })

        r.ring.set(null)
        r.manager.checkIfDue()
        assertEquals("checks resume once the alarm is over", 1, r.client.calls)
    }

    // ---- what a check decides --------------------------------------------------------------------------

    @Test fun `a newer release is announced once`() = runTest {
        val r = rig()
        r.manager.checkIfDue()
        assertEquals(release("1.0.6"), r.manager.prompt.value)
        assertEquals(UpdateStatus.Available(release("1.0.6")), r.manager.status.value)

        r.manager.dismissPrompt()
        r.time.millis = start + 2 * day
        r.manager.checkIfDue()
        assertEquals("a second check in the same session finds it again but does not nag", 2, r.client.calls)
        assertNull(r.manager.prompt.value)
        assertEquals(UpdateStatus.Available(release("1.0.6")), r.manager.status.value)
    }

    @Test fun `the same or an older release is not an update`() = runTest {
        listOf("1.0.5", "1.0.4", "0.9.9").forEach { latest ->
            val r = rig(answer = { LookupResult.Found(release(latest)) })
            r.manager.checkIfDue()
            assertEquals(latest, UpdateStatus.UpToDate, r.manager.status.value)
            assertNull(r.manager.prompt.value)
        }
    }

    @Test fun `a debug suffix on the installed version does not matter`() = runTest {
        val same = rig(installed = "1.0.6-debug")
        same.manager.checkIfDue()
        assertEquals(UpdateStatus.UpToDate, same.manager.status.value)
        val older = rig(installed = "1.0.5-debug")
        older.manager.checkIfDue()
        assertEquals(UpdateStatus.Available(release("1.0.6")), older.manager.status.value)
    }

    @Test fun `an installed version that cannot be read never prompts`() = runTest {
        listOf("", "dev", "1.0", "snapshot").forEach { installed ->
            val r = rig(installed = installed)
            r.manager.checkIfDue()
            assertEquals(installed, UpdateStatus.UpToDate, r.manager.status.value)
            assertNull(r.manager.prompt.value)
        }
    }

    @Test fun `no release yet counts as up to date and as a successful check`() = runTest {
        val r = rig(answer = { LookupResult.NoRelease("HTTP 404") })
        r.manager.checkIfDue()
        assertEquals(UpdateStatus.UpToDate, r.manager.status.value)
        assertEquals(start, r.settings.state.value.lastUpdateCheckAt)
        assertNull(r.manager.prompt.value)
    }

    @Test fun `a manual check reports the release but does not raise the popup`() = runTest {
        val r = rig()
        r.manager.checkNow()
        assertEquals(UpdateStatus.Available(release("1.0.6")), r.manager.status.value)
        assertNull(r.manager.prompt.value)
        assertEquals(start, r.settings.state.value.lastUpdateCheckAt)
    }

    // ---- skipping --------------------------------------------------------------------------------------

    @Test fun `a skipped version is not announced again but a newer one is`() = runTest {
        val r = rig()
        r.manager.checkIfDue()
        r.manager.skip(r.manager.prompt.value!!)
        assertEquals("1.0.6", r.settings.state.value.skippedUpdateVersion)
        assertNull(r.manager.prompt.value)

        // A new session (fresh manager, same stored settings) after the next daily check.
        val sameRelease = rig(settings = r.settings.state.value.copy(lastUpdateCheckAt = 0))
        sameRelease.manager.checkIfDue()
        assertEquals(UpdateStatus.Available(release("1.0.6")), sameRelease.manager.status.value)
        assertNull("skipped, so no popup", sameRelease.manager.prompt.value)

        val newer = rig(settings = r.settings.state.value.copy(lastUpdateCheckAt = 0), answer = { LookupResult.Found(release("1.0.7")) })
        newer.manager.checkIfDue()
        assertEquals(release("1.0.7"), newer.manager.prompt.value)
    }

    @Test fun `skipping a version also covers anything older that turns up`() = runTest {
        val r = rig(settings = AppSettings(onboardingDone = true, skippedUpdateVersion = "1.0.9"), answer = { LookupResult.Found(release("1.0.8")) }, installed = "1.0.5")
        r.manager.checkIfDue()
        assertNull(r.manager.prompt.value)
    }

    @Test fun `a garbled skipped version is ignored`() = runTest {
        val r = rig(settings = AppSettings(onboardingDone = true, skippedUpdateVersion = "banana"))
        r.manager.checkIfDue()
        assertEquals(release("1.0.6"), r.manager.prompt.value)
    }

    @Test fun `skipping lets the popup go away at once`() = runTest {
        val r = rig()
        r.manager.checkIfDue()
        r.manager.skip(r.manager.prompt.value!!)
        assertNull(r.manager.prompt.value)
    }

    @Test fun `Later only dismisses`() = runTest {
        val r = rig()
        r.manager.checkIfDue()
        r.manager.dismissPrompt()
        assertNull(r.manager.prompt.value)
        assertEquals("", r.settings.state.value.skippedUpdateVersion)
    }

    // ---- failures --------------------------------------------------------------------------------------

    @Test fun `a failure is quiet, remembered for the session and not counted as a check`() = runTest {
        val r = rig(answer = { LookupResult.Failed(FailureKind.OFFLINE, "UnknownHostException") })
        r.manager.checkIfDue()
        assertEquals(UpdateStatus.Failed(FailureKind.OFFLINE), r.manager.status.value)
        assertEquals("the daily clock is untouched", 0L, r.settings.state.value.lastUpdateCheckAt)
        assertNull(r.manager.prompt.value)
        assertTrue(r.log.entries.any { it.startsWith("W Update") && "OFFLINE" in it })
    }

    @Test fun `a failed check is retried at most once an hour`() = runTest {
        val r = rig(answer = { LookupResult.Failed(FailureKind.RATE_LIMITED, "HTTP 403") })
        r.manager.checkIfDue()
        r.time.millis = start + hour - 1
        r.manager.checkIfDue()
        assertEquals(1, r.client.calls)
        r.time.millis = start + hour
        r.manager.checkIfDue()
        assertEquals(2, r.client.calls)
    }

    @Test fun `a success clears the failure back-off`() = runTest {
        var fail = true
        val r = rig(answer = { if (fail) LookupResult.Failed(FailureKind.OFFLINE, "x") else LookupResult.Found(release("1.0.5")) })
        r.manager.checkIfDue()
        fail = false
        r.time.millis = start + hour
        r.manager.checkIfDue()
        assertEquals(UpdateStatus.UpToDate, r.manager.status.value)
        assertEquals(start + hour, r.settings.state.value.lastUpdateCheckAt)
    }

    @Test fun `a manual check ignores the failure back-off`() = runTest {
        val r = rig(answer = { LookupResult.Failed(FailureKind.TIMEOUT, "x") })
        r.manager.checkIfDue()
        r.manager.checkNow()
        assertEquals(2, r.client.calls)
    }

    @Test fun `a client that throws is contained`() = runTest {
        val r = rig(answer = { throw IllegalStateException("boom") })
        r.manager.checkIfDue()
        assertEquals(UpdateStatus.Failed(FailureKind.UNEXPECTED), r.manager.status.value)
    }

    @Test fun `cancelling a check leaves no Checking status behind`() = runTest {
        val r = rig(answer = { throw CancellationException("screen left") })
        var cancelled = false
        try {
            r.manager.checkIfDue()
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("cancellation is passed on, not swallowed", cancelled)
        assertEquals(UpdateStatus.Idle, r.manager.status.value)
    }

    @Test fun `a second request while one is running is dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val r = rig()
        val slow = object : ReleaseClient {
            var calls = 0
            override suspend fun latest(): LookupResult {
                calls++
                gate.await()
                return LookupResult.Found(release("1.0.6"))
            }
        }
        val manager = UpdateManager(
            slow, r.settings, r.time, r.log, r.ring, UpdateEnvironment("1.0.5", true, repo),
            CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        val first = launch(UnconfinedTestDispatcher(testScheduler)) { manager.checkIfDue() }
        assertEquals(UpdateStatus.Checking, manager.status.value)
        manager.checkNow()
        manager.checkIfDue()
        assertEquals("the overlapping requests did not start another lookup", 1, slow.calls)
        gate.complete(Unit)
        first.join()
        assertEquals(UpdateStatus.Available(release("1.0.6")), manager.status.value)
        assertEquals(1, slow.calls)
    }

    // ---- the switch ------------------------------------------------------------------------------------

    @Test fun `the switch is stored`() = runTest {
        val r = rig()
        r.manager.setEnabled(false)
        assertTrue(!r.settings.state.first().updateChecksEnabled)
        r.manager.setEnabled(true)
        assertTrue(r.settings.state.first().updateChecksEnabled)
    }
}
