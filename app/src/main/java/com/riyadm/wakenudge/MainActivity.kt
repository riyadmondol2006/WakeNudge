package com.riyadm.wakenudge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { Text("WakeNudge") } }
    }

    companion object {
        const val ACTION_SHOW_ALARMS = "com.riyadm.wakenudge.action.SHOW_ALARMS"
        const val ACTION_SHOW_TIMERS = "com.riyadm.wakenudge.action.SHOW_TIMERS"
        const val ACTION_SHOW_SLEEP = "com.riyadm.wakenudge.action.SHOW_SLEEP"
    }
}
