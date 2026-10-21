package com.riyadm.wakenudge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
        if (savedInstanceState == null) viewModel.handleIntent(intent)
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
    }

    companion object {
        const val ACTION_SHOW_ALARMS = "com.riyadm.wakenudge.action.SHOW_ALARMS"
        const val ACTION_SHOW_TIMERS = "com.riyadm.wakenudge.action.SHOW_TIMERS"
        const val ACTION_SHOW_SLEEP = "com.riyadm.wakenudge.action.SHOW_SLEEP"
    }
}
