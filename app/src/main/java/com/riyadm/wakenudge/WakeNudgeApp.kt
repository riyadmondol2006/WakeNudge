package com.riyadm.wakenudge

import android.app.Application
import com.riyadm.wakenudge.di.AppScope
import com.riyadm.wakenudge.engine.PerAlarmSettingsCopy
import com.riyadm.wakenudge.engine.RingController
import com.riyadm.wakenudge.engine.RingCrashGuard
import com.riyadm.wakenudge.engine.RingLedger
import com.riyadm.wakenudge.engine.StartupRepair
import com.riyadm.wakenudge.engine.audio.AlarmAudioEngine
import com.riyadm.wakenudge.engine.notify.Notifier
import com.riyadm.wakenudge.health.ExitPostMortem
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class WakeNudgeApp : Application() {
    @Inject lateinit var notifier: Notifier
    @Inject lateinit var ring: RingController
    @Inject lateinit var audio: AlarmAudioEngine
    @Inject lateinit var crashGuard: RingCrashGuard
    @Inject lateinit var startupRepair: StartupRepair
    @Inject lateinit var perAlarmSettingsCopy: PerAlarmSettingsCopy
    @Inject lateinit var exitPostMortem: ExitPostMortem
    @Inject lateinit var ringLedger: RingLedger
    @Inject @AppScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // The ring screen shares this process with the sound: if it crashes mid-ring, the alarm is re-armed first.
        crashGuard.install()
        // Channels must exist before any receiver posts a notification, including receivers that start the process.
        notifier.ensureChannels()
        // Housekeeping runs at every process start, including the one an alarm wakes: whatever it hits, it must not crash.
        appScope.launch {
            runCatching {
                audio.restoreSystemVolumeIfStale()
                ring.recoverStaleState()
            }
            perAlarmSettingsCopy.runOnce()
            // Before anything below settles the saved ring: it is what says a ring was on when the last process ended.
            exitPostMortem.report()
            // A force-stop wipes every alarm without telling the app; this start is the first chance to put them back.
            val wiped = runCatching { startupRepair.triggersWiped() }.getOrDefault(false)
            runCatching { startupRepair.repairIfNeeded() }
            // A ring the last process left behind comes back, or is told about if it cannot.
            runCatching { ring.reviveDurableRing(triggersWiped = wiped) }
            // Last, when all of the above has recorded what it found: an alarm that did not ring on time is explained.
            ringLedger.update()
        }
    }
}
