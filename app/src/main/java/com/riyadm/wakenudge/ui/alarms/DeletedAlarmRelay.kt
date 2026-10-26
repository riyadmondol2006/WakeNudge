package com.riyadm.wakenudge.ui.alarms

import com.riyadm.wakenudge.domain.model.Alarm
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Carries an alarm deleted in the editor back to the alarm list, which then offers Undo for it exactly as for one
 * deleted from the list. The list's view model sits below the editor in the back stack, so it is already listening.
 */
@Singleton
class DeletedAlarmRelay @Inject constructor() {
    private val deleted = MutableSharedFlow<Alarm>(extraBufferCapacity = 4)
    val alarms: SharedFlow<Alarm> = deleted.asSharedFlow()

    fun post(alarm: Alarm) {
        deleted.tryEmit(alarm)
    }
}
