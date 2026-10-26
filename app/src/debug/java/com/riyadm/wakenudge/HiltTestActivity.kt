package com.riyadm.wakenudge

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * An empty Hilt-aware activity (debug builds only): composables that call hiltViewModel() need one to live in when UI
 * tests exercise them. The release APK does not contain it.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
