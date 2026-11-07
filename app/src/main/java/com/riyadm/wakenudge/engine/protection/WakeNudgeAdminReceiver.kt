package com.riyadm.wakenudge.engine.protection

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.riyadm.wakenudge.R

/**
 * Device admin with no policies (`res/xml/device_admin.xml`). While it is active Android refuses Force stop and uninstall,
 * so a half-asleep user cannot remove the alarm; it can be switched off in the app's Protection screen whenever no alarm
 * rings or is snoozed, and in Settings, Security, Device admin apps at any time. Only the system can call this receiver
 * (`BIND_DEVICE_ADMIN`), and it does nothing but say what switching it off means.
 */
class WakeNudgeAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.protection_admin_disable_warning)
}
