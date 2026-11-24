package com.riyadm.wakenudge.engine.sleeptrack

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.riyadm.wakenudge.core.TimeSource
import com.riyadm.wakenudge.data.sleep.SleepRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs the retention rules (clips after 7 days, nights after a year) and arranges to run again when the next clip
 * expires, with an inexact, non-waking alarm of its own: it fires the next time the phone is awake anyway and never
 * competes with an alarm. That alarm does not survive a restart; the rules then run the next time sleep tracking is
 * opened or a night ends, and expired clips are never shown or played meanwhile.
 */
@Singleton
class SleepCleanup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SleepRepository,
    private val time: TimeSource,
) {
    suspend fun run(runningId: Long?) {
        val next = repo.cleanUp(time.nowMillis(), runningId)
        schedule(next)
    }

    private fun schedule(atMs: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, SleepTrackIds.REQUEST_CLEANUP, Intent(context, SleepCleanupReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching {
            if (atMs == null) am.cancel(pi) else am.set(AlarmManager.RTC, atMs + 60_000L, pi)
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SleepEntryPoint {
    fun cleanup(): SleepCleanup
    fun tracker(): SleepTracker
}

/** Deletes expired snore clips. Not exported; only its own alarm reaches it. */
class SleepCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Sleep data is in credential-encrypted storage: nothing to do before the first unlock.
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, SleepEntryPoint::class.java)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { entry.cleanup().run(runningId = if (entry.tracker().isActive) -1 else null) }
            } finally {
                pending.finish()
            }
        }
    }
}
