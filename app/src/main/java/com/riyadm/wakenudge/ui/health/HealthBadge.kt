package com.riyadm.wakenudge.ui.health

import com.riyadm.wakenudge.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import com.riyadm.wakenudge.health.launch

/** A small dot on the health icon when something may stop an alarm from ringing. Invisible when all is well. */
@Composable
fun HealthBadge(viewModel: HealthBadgeViewModel = hiltViewModel()) {
    val worst by viewModel.worst.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    if (worst >= Severity.WARNING) {
        val attention = stringResource(R.string.health_badge_attention)
        Box(
            Modifier
                .offset(x = 14.dp, y = (-2).dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(if (worst == Severity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                // Merges into the health button, which then says that something needs attention, not just its name.
                .semantics { contentDescription = attention },
        )
    }
}

/** A card on the Alarms screen when notifications, the full-screen alarm or sound are blocked, with a one-tap fix. */
@Composable
fun HealthBanner(viewModel: HealthBadgeViewModel = hiltViewModel()) {
    val item by viewModel.urgent.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val shown = item ?: return
    androidx.compose.material3.Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Column(Modifier.padding(20.dp)) {
            androidx.compose.material3.Text(
                androidx.compose.ui.res.stringResource(shown.title), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            androidx.compose.material3.Text(shown.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
            shown.fix?.let { fix ->
                androidx.compose.material3.Button(onClick = { fix.launch(context) }, modifier = Modifier.padding(top = 12.dp)) {
                    androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(fix.label))
                }
            }
        }
    }
}
