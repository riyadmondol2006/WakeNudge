package com.riyadm.wakenudge.ui.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a change and the re-arming that goes with it to the end, even if the screen closes half-way. Leaving a screen
 * cancels its view model's coroutines, and a cancellation between "vacation saved" and "alarms rescheduled" would leave
 * alarms that look paused but still ring, or that look on but are not armed.
 */
internal fun ViewModel.persist(block: suspend CoroutineScope.() -> Unit): Job =
    viewModelScope.launch { withContext(NonCancellable, block) }
