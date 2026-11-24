package com.riyadm.wakenudge.ui.sounds

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.riyadm.wakenudge.engine.sounds.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class SleepSoundsViewModel @Inject constructor(val store: SleepMixStore, @ApplicationContext private val context: Context) : ViewModel() {
    fun toggle() {
        if (store.playing.value) context.startService(Intent(context, SleepSoundsService::class.java).setAction(SleepSoundsService.STOP))
        else runCatching { ContextCompat.startForegroundService(context, Intent(context, SleepSoundsService::class.java)) }
            .onFailure { store.error.value = true }
    }
}
