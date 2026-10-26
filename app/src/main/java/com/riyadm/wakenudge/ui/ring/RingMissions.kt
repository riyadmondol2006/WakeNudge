package com.riyadm.wakenudge.ui.ring

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.missions.MissionHost

/** Bridge between the ring screen and the missions module; keeps [RingScreen] free of mission internals. */
@Composable
fun RingMissionContent(
    missions: List<MissionConfig>,
    onInteraction: () -> Unit,
    onProgress: (Int) -> Unit,
    onComplete: () -> Unit,
    firstMission: Int = 0,
) {
    MissionHost(
        missions = missions,
        modifier = Modifier,
        onInteraction = onInteraction,
        onProgress = onProgress,
        firstMission = firstMission,
        onComplete = onComplete,
    )
}
