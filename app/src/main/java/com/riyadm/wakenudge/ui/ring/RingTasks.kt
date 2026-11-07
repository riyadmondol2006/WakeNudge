package com.riyadm.wakenudge.ui.ring

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * One ring has one ring screen task, so the Overview never shows two alarm cards. A ring screen that comes back (pulled back
 * after a swiped card, or started again while an older start was still being taken down) removes any other task of the app
 * whose root is a ring screen, normal or safe.
 */
object RingTasks {
    private val ringScreens = setOf(RingActivity::class.java.name, SafeRingActivity::class.java.name)

    /** Removes every ring screen task except [keepTaskId]. Returns how many were removed. */
    fun removeOthers(context: Context, keepTaskId: Int): Int {
        val tasks = runCatching { context.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty() }.getOrDefault(emptyList())
        var removed = 0
        for (task in tasks) {
            val info = runCatching { task.taskInfo }.getOrNull() ?: continue
            if (idOf(info) == keepTaskId) continue
            val root = info.baseIntent?.component?.className ?: info.baseActivity?.className
            if (root in ringScreens && runCatching { task.finishAndRemoveTask() }.isSuccess) removed++
        }
        return removed
    }

    @Suppress("DEPRECATION")
    private fun idOf(info: ActivityManager.RecentTaskInfo): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.persistentId

    /** How many ring screen tasks the app has; for the diagnostics and tests. */
    fun count(context: Context): Int = runCatching {
        context.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty().count {
            val info = it.taskInfo
            (info?.baseIntent?.component?.className ?: info?.baseActivity?.className) in ringScreens
        }
    }.getOrDefault(0)
}
