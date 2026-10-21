package com.riyadm.wakenudge.widget

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.material3.ColorProviders
import com.riyadm.wakenudge.MainActivity
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.theme.DarkColors
import com.riyadm.wakenudge.ui.theme.LightColors

/** The brand palette for Android < 12, or when the user switched wallpaper colours off in the app. */
private val BrandColors by lazy { ColorProviders(light = LightColors, dark = DarkColors) }

/** Glance Material 3 theme: wallpaper-derived dynamic colour where the platform offers it and the user allows it. */
@Composable
internal fun WidgetTheme(dynamicColor: Boolean, content: @Composable () -> Unit) {
    if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        GlanceTheme(content = content)
    } else {
        GlanceTheme(colors = BrandColors, content = content)
    }
}

/**
 * The rounded widget card. Android 12+ rounds app widgets with a system radius; earlier launchers (and Glance's
 * `cornerRadius`, a no-op there) leave square corners, so a tinted rounded backdrop is drawn instead.
 */
@Composable
internal fun WidgetSurface(modifier: GlanceModifier = GlanceModifier, content: @Composable () -> Unit) {
    val background = GlanceTheme.colors.widgetBackground
    val root = modifier.fillMaxSize().appWidgetBackground()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Box(
            modifier = root.background(background).cornerRadius(android.R.dimen.system_app_widget_background_radius),
            content = content,
        )
    } else {
        Box(modifier = root) {
            Image(
                provider = ImageProvider(R.drawable.widget_backdrop),
                contentDescription = null,
                modifier = GlanceModifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                colorFilter = ColorFilter.tint(background),
            )
            content()
        }
    }
}

/** Breakpoints at which the widgets switch layouts. */
internal object WidgetBreakpoints {
    val WIDE = 190.dp
    val MID = 64.dp
    val TALL = 100.dp
}

private fun appIntent(context: Context, action: String): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(action)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

/** Opens the app on its alarm list, on [alarmId] when given (same extra the notifications use). */
internal fun openAlarmsAction(context: Context, alarmId: Long? = null): Action {
    val intent = appIntent(context, MainActivity.ACTION_SHOW_ALARMS)
    if (alarmId != null) {
        // A distinct data URI keeps the PendingIntents of different list rows from collapsing into one.
        intent.putExtra("id", alarmId).setData("wakenudge://widget/alarm/$alarmId".toUri())
    }
    return actionStartActivity(intent)
}

/** The standard "create an alarm" request, addressed to this app so no chooser appears. */
internal fun addAlarmAction(context: Context): Action =
    actionStartActivity(appIntent(context, AlarmClock.ACTION_SET_ALARM))
