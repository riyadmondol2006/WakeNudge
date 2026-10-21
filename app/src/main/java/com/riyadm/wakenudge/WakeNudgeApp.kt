package com.riyadm.wakenudge

import android.app.Application
import com.riyadm.wakenudge.di.AppScope
import com.riyadm.wakenudge.engine.RingController
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
    @Inject @AppScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Channels must exist before any receiver posts a notification, including receivers that start the process.
        notifier.ensureChannels()
        appScope.launch {
            audio.restoreSystemVolumeIfStale()
            ring.recoverStaleState()
        }
    }
}
