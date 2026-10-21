package com.riyadm.wakenudge.engine.notify

import android.app.NotificationManager

/** Pure decision behind [Notifier.ringScreenMayNotAppear], kept apart so every combination can be unit-tested. */
object RingUiFallback {
    fun mayNotAppear(sdkInt: Int, notificationsEnabled: Boolean, ringChannelImportance: Int?, fullScreenAllowed: Boolean): Boolean =
        !notificationsEnabled ||
            (ringChannelImportance != null && ringChannelImportance < NotificationManager.IMPORTANCE_HIGH) ||
            (sdkInt >= 34 && !fullScreenAllowed)
}
