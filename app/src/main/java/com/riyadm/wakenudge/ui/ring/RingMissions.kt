package com.riyadm.wakenudge.ui.ring

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.missions.MissionBackup
import com.riyadm.wakenudge.missions.MissionHost

/** What replaces a mission that cannot be done on this ring; provided by [RingScreen] around the alarm's own missions. */
val LocalMissionBackup = staticCompositionLocalOf<MissionBackup?> { null }

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
        progressKeptElsewhere = true,
        backup = LocalMissionBackup.current,
        onComplete = onComplete,
    )
}
