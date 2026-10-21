package com.riyadm.wakenudge

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * An empty Hilt-aware activity (debug builds of the GitHub flavour only): composables that call hiltViewModel() need
 * one to live in when UI tests exercise them. The release APKs do not contain it.
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
