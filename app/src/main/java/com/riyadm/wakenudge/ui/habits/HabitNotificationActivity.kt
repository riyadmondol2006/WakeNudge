package com.riyadm.wakenudge.ui.habits

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.riyadm.wakenudge.domain.model.AppSettings
import com.riyadm.wakenudge.domain.repository.SettingsRepository
import com.riyadm.wakenudge.ui.theme.WakeNudgeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class HabitNotificationActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val current by produceState<AppSettings?>(null) {
                settings.settings.collect { value = it }
            }
            val value = current ?: return@setContent
            WakeNudgeTheme(themeMode = value.themeMode, dynamicColor = value.dynamicColor, haptics = value.haptics) {
                Surface(Modifier.fillMaxSize()) {
                    HabitsScreen(onBack = { finish() })
                }
            }
        }
    }
}
