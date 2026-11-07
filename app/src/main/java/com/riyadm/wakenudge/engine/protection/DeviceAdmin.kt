package com.riyadm.wakenudge.engine.protection

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WakeNudge's own device admin, with no policies. Turning it on always goes through Android's own confirmation screen;
 * turning it off is [deactivate], or Settings, Security, Device admin apps. Every call is guarded: a phone that will not
 * say counts as "not active", so the app never claims a protection it does not have.
 */
@Singleton
class DeviceAdmin @Inject constructor(@ApplicationContext private val context: Context) {
    val component: ComponentName get() = ComponentName(context, WakeNudgeAdminReceiver::class.java)

    private val manager: DevicePolicyManager? get() = context.getSystemService(DevicePolicyManager::class.java)

    fun isActive(): Boolean = try {
        manager?.isAdminActive(component) == true
    } catch (_: Throwable) {
        false
    }

    /** Android's "Activate device admin app?" screen, with [explanation] shown on it. */
    fun activationIntent(explanation: String): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
        .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)

    /** Switches it off. Returns false if Android refused, in which case the system route still works. */
    fun deactivate(): Boolean = try {
        manager?.removeActiveAdmin(component)
        true
    } catch (_: Throwable) {
        false
    }
}
