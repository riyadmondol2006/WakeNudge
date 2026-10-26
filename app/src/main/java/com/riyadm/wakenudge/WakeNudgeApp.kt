package com.riyadm.wakenudge

import android.app.Application
import com.riyadm.wakenudge.di.AppScope
import com.riyadm.wakenudge.engine.RingController
import com.riyadm.wakenudge.engine.RingCrashGuard
import com.riyadm.wakenudge.engine.StartupRepair
import com.riyadm.wakenudge.engine.audio.AlarmAudioEngine
import com.riyadm.wakenudge.engine.notify.Notifier
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
            // A force-stop wipes every alarm without telling the app; this start is the first chance to put them back.
            runCatching { startupRepair.repairIfNeeded() }
        }
    }
}
