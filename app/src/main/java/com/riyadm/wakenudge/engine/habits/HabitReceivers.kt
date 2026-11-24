package com.riyadm.wakenudge.engine.habits

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.riyadm.wakenudge.core.TimeSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface HabitEntryPoint {
    fun handler(): HabitReminderHandler
    fun habitScheduler(): HabitReminderScheduler
    fun time(): TimeSource
}

private fun Context.habits(): HabitEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, HabitEntryPoint::class.java)

private fun BroadcastReceiver.runHabitWork(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            block()
        } catch (t: Throwable) {
            Log.e("WakeNudge", "habit receiver work failed", t)
        } finally {
            pending.finish()
        }
    }
}

class HabitReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val habitId = intent.getLongExtra(HabitReminderIntents.EXTRA_HABIT_ID, 0)
        val epochDay = intent.getLongExtra(HabitReminderIntents.EXTRA_EPOCH_DAY, Long.MIN_VALUE)
        if (habitId <= 0) return
        val handler = context.habits().handler()
        runHabitWork {
            when (intent.action) {
                HabitReminderIntents.ACTION_REMIND -> handler.remind(habitId, epochDay)
                HabitReminderIntents.ACTION_DONE -> handler.done(habitId, epochDay)
            }
        }
    }
}

class HabitSystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val habits = context.habits()
        runHabitWork {
            habits.time().refreshZone()
            habits.habitScheduler().rescheduleAll()
        }
    }

    companion object {
        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}
