package com.riyadm.wakenudge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.riyadm.wakenudge.health.HealthFix
import com.riyadm.wakenudge.health.HealthFixKind
import com.riyadm.wakenudge.health.HealthIntents
import com.riyadm.wakenudge.health.launch
import com.riyadm.wakenudge.ui.MainViewModel
import com.riyadm.wakenudge.ui.WakeNudgeRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Hold the splash until settings are loaded so the first frame already has the right theme.
        installSplashScreen().setKeepOnScreenCondition { viewModel.settings.value == null }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            viewModel.handleIntent(intent)
            openHealthFix(intent)
        }
        setContent { WakeNudgeRoot(viewModel) }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onForeground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIntent(intent)
        openHealthFix(intent)
    }

    /**
     * The fix button of the evening check's notification, opened from here so that a phone without that exact Settings
     * screen gets the closest one it has. Any app can send this activity this; all it can do is open a Settings screen.
     */
    private fun openHealthFix(intent: Intent?) {
        if (intent?.action != ACTION_HEALTH_FIX) return
        runCatching {
            val kind = intent.getStringExtra(EXTRA_HEALTH_FIX)?.let { name -> HealthFixKind.entries.firstOrNull { it.name == name } } ?: return
            HealthFix(R.string.notif_tonight_fix, HealthIntents.forKind(kind, packageName), kind).launch(this)
        }
    }

    companion object {
        const val ACTION_SHOW_ALARMS = "com.riyadm.wakenudge.action.SHOW_ALARMS"
        const val ACTION_SHOW_TIMERS = "com.riyadm.wakenudge.action.SHOW_TIMERS"
        const val ACTION_SHOW_SLEEP = "com.riyadm.wakenudge.action.SHOW_SLEEP"
        const val ACTION_SHOW_HEALTH = "com.riyadm.wakenudge.action.SHOW_HEALTH"
        const val ACTION_HEALTH_FIX = "com.riyadm.wakenudge.action.HEALTH_FIX"
        const val EXTRA_HEALTH_FIX = "health_fix"
    }
}
