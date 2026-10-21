package com.riyadm.wakenudge.health

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.net.toUri
import com.riyadm.wakenudge.engine.notify.Channels

/** Ordered from harmless to critical, so the worst of a set is simply the maximum. */
enum class Severity { OK, INFO, WARNING, ERROR }

/** The Settings screens a health item can send the user to. */
enum class HealthFixKind {
    APP_NOTIFICATIONS,
    RING_CHANNEL,
    EXACT_ALARMS,
    FULL_SCREEN_INTENT,
    BATTERY_OPTIMIZATION,
    APP_DETAILS,
    DND_SETTINGS,
    DATE_TIME,
    SOUND,
    TTS_SETTINGS,
}

/** A one-tap remedy. Use [launch] to start it safely; [intent] is exposed for callers that want their own handling. */
data class HealthFix(@StringRes val label: Int, val intent: Intent, val kind: HealthFixKind)

/**
 * One line of the Alarm Health screen. [title] names the check, [detail] is an honest explanation of what was found
 * and what it means for alarms, and [fix] (when there is one) opens the screen that resolves it.
 */
data class HealthItem(
    val id: String,
    val severity: Severity,
    @StringRes val title: Int,
    val detail: String,
    val fix: HealthFix? = null,
)

data class HealthReport(val items: List<HealthItem>) {
    val worstSeverity: Severity get() = items.maxOfOrNull { it.severity } ?: Severity.OK

    /** Problems first, in the order they were checked within a severity. */
    val bySeverity: List<HealthItem> get() = items.sortedByDescending { it.severity }

    fun count(severity: Severity): Int = items.count { it.severity == severity }
}

/** Real Settings intents. Built with `apply` so they also construct under the JVM's stubbed Android classes. */
object HealthIntents {

    // The action strings are plain constants that resolve to nothing on older versions; the evaluator only offers the
    // newer screens on versions that have them, and HealthFix.launch falls back to app settings regardless.
    @SuppressLint("InlinedApi")
    fun forKind(kind: HealthFixKind, packageName: String): Intent = when (kind) {
        HealthFixKind.APP_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        HealthFixKind.RING_CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, Channels.RING)
        }
        HealthFixKind.EXACT_ALARMS -> Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = "package:$packageName".toUri()
        }
        HealthFixKind.FULL_SCREEN_INTENT -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
            data = "package:$packageName".toUri()
        }
        HealthFixKind.BATTERY_OPTIMIZATION -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        HealthFixKind.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:$packageName".toUri()
        }
        // The DND "priority only" screen has no public constant, but this action exists on AOSP-based Settings apps.
        HealthFixKind.DND_SETTINGS -> Intent("android.settings.ZEN_MODE_PRIORITY_SETTINGS")
        HealthFixKind.DATE_TIME -> Intent(Settings.ACTION_DATE_SETTINGS)
        HealthFixKind.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
        HealthFixKind.TTS_SETTINGS -> Intent("com.android.settings.TTS_SETTINGS")
    }

    /** Screens to try, in order, when the first choice does not exist on this device's Settings app. */
    fun fallbacksFor(kind: HealthFixKind, packageName: String): List<Intent> = when (kind) {
        HealthFixKind.DND_SETTINGS -> listOf(Intent(Settings.ACTION_SOUND_SETTINGS))
        HealthFixKind.TTS_SETTINGS -> listOf(Intent(Settings.ACTION_SETTINGS))
        HealthFixKind.RING_CHANNEL -> listOf(forKind(HealthFixKind.APP_NOTIFICATIONS, packageName))
        HealthFixKind.EXACT_ALARMS, HealthFixKind.FULL_SCREEN_INTENT, HealthFixKind.BATTERY_OPTIMIZATION ->
            listOf(forKind(HealthFixKind.APP_DETAILS, packageName))
        else -> emptyList()
    }
}

/**
 * Starts the fix, falling back to a more general Settings screen when a vendor's Settings app lacks the exact one.
 * Returns whether anything could be opened.
 */
fun HealthFix.launch(context: Context): Boolean {
    val candidates = listOf(intent) + HealthIntents.fallbacksFor(kind, context.packageName) +
        HealthIntents.forKind(HealthFixKind.APP_DETAILS, context.packageName)
    for (candidate in candidates) {
        try {
            context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // Try the next, more general, screen.
        } catch (_: SecurityException) {
            // Some vendors guard their settings screens; same remedy.
        }
    }
    return false
}
