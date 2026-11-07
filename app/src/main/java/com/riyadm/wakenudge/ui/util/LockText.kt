package com.riyadm.wakenudge.ui.util

import android.content.res.Resources
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.scheduling.EditLock

/**
 * The one message every side door shows when edit protection refuses a change: the app, a widget, the tile, a
 * notification, the backup screen. The same words wherever it was tried, so nobody wonders whether another way works.
 */
object LockText {
    fun message(res: Resources, lock: EditLock): String =
        if (lock.whileRinging) res.getString(R.string.lock_message_ringing)
        else res.getString(R.string.lock_message_window, duration(res, lock.minutes))

    /** "45 min", "8 h", "7 h 30 min": a 12-hour window reads better than "720 min". */
    fun duration(res: Resources, minutes: Int): String {
        val m = minutes.coerceAtLeast(1)
        return when {
            m < 60 -> res.getString(R.string.unit_minutes, m)
            m % 60 == 0 -> res.getString(R.string.lock_duration_hours, m / 60)
            else -> res.getString(R.string.lock_duration_hours_minutes, m / 60, m % 60)
        }
    }
}

/** Says every change that edit protection refused, with [LockText.message]. */
@androidx.compose.runtime.Composable
fun LockNotices(notices: kotlinx.coroutines.flow.Flow<EditLock>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    androidx.compose.runtime.LaunchedEffect(notices) {
        notices.collect { android.widget.Toast.makeText(context, LockText.message(resources, it), android.widget.Toast.LENGTH_LONG).show() }
    }
}
