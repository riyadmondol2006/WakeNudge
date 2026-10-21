package com.riyadm.wakenudge.ui.health

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.health.Severity

/** A small dot on the health icon when something may stop an alarm from ringing. Invisible when all is well. */
@Composable
fun HealthBadge(viewModel: HealthBadgeViewModel = hiltViewModel()) {
    val worst by viewModel.worst.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    if (worst >= Severity.WARNING) {
        Box(
            Modifier
                .offset(x = 14.dp, y = (-2).dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(if (worst == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary),
        )
    }
}
