package com.riyadm.wakenudge.ui.onboarding

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AlarmOn
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.riyadm.wakenudge.BuildConfig
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.components.rememberReduceMotion
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import com.riyadm.wakenudge.ui.theme.PolygonShape
import com.riyadm.wakenudge.ui.theme.WakeMotion
import com.riyadm.wakenudge.update.ProjectLinks
import kotlinx.coroutines.launch

private const val PAGES = 5

/** [openLink] is how the "Open source" line reaches the browser; the default hands the address to the system. */
@Composable
fun OnboardingScreen(onFinish: () -> Unit, modifier: Modifier = Modifier, openLink: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val open: (String) -> Unit = openLink ?: { url -> ProjectLinks.open(context, url) }
    val pager = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()
    val haptics = LocalWakeHaptics.current
    val last = pager.currentPage == PAGES - 1

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            if (!last) TextButton(onClick = { pager.requestScrollToPage(PAGES - 1) }) { Text(stringResource(R.string.onboarding_skip)) }
            else Spacer(Modifier.height(48.dp))
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            when (page) {
                0 -> InfoPage(
                    Icons.Outlined.AlarmOn, Blobs.Sunny, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body,
                    MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer,
                    footer = { OpenSourceLink(onClick = { open(ProjectLinks.REPO) }) },
                )
                1 -> InfoPage(Icons.Outlined.Lock, Blobs.Cookie, R.string.onboarding_reliable_title, R.string.onboarding_reliable_body, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                2 -> InfoPage(Icons.Outlined.Psychology, Blobs.Clover, R.string.onboarding_missions_title, R.string.onboarding_missions_body, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                // The GitHub APK may go online for the update check, so it must not claim to be "offline" with this icon.
                3 -> InfoPage(if (BuildConfig.UPDATE_CHECKS) Icons.Outlined.PrivacyTip else Icons.Outlined.WifiOff, Blobs.Flower, R.string.onboarding_privacy_title, R.string.onboarding_privacy_body, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                else -> PermissionsPage()
            }
        }
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(PAGES) { i ->
                    val selected = pager.currentPage == i
                    val w by animateDpAsState(if (selected) 28.dp else 8.dp, WakeMotion.fastSpatial(), label = "dot")
                    val c by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "dotColor")
                    Box(Modifier.width(w).height(8.dp).clip(CircleShape).background(c))
                }
            }
            Button(
                onClick = {
                    haptics.click()
                    if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                modifier = Modifier.height(56.dp),
            ) { Text(stringResource(if (last) R.string.onboarding_start else R.string.onboarding_next)) }
        }
    }
}

@Composable
private fun InfoPage(
    icon: ImageVector,
    shape: androidx.graphics.shapes.RoundedPolygon,
    title: Int,
    body: Int,
    container: androidx.compose.ui.graphics.Color,
    onContainer: androidx.compose.ui.graphics.Color,
    footer: (@Composable () -> Unit)? = null,
) {
    val reduce = rememberReduceMotion()
    val t = rememberInfiniteTransition(label = "onb")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing), RepeatMode.Restart), label = "spin")
    val orbit by t.animateFloat(0f, 360f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart), label = "orbit")
    // Scrolls, so the text and the link under it stay reachable at the largest font size on a small phone.
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(280.dp), contentAlignment = Alignment.Center) {
            // Small satellites circle the hero shape for a bit of life.
            listOf(0f to MaterialTheme.colorScheme.tertiary, 120f to MaterialTheme.colorScheme.secondary, 240f to MaterialTheme.colorScheme.primary).forEach { (phase, color) ->
                val angle = Math.toRadians(((if (reduce) 0f else orbit) + phase).toDouble())
                Box(
                    Modifier
                        .offset(x = (120 * Math.cos(angle)).dp, y = (120 * Math.sin(angle)).dp)
                        .size(26.dp)
                        .clip(PolygonShape(Blobs.Circle))
                        .background(color.copy(alpha = 0.6f)),
                )
            }
            Box(
                Modifier.size(200.dp).rotate(if (reduce) 0f else spin).clip(PolygonShape(shape)).background(container),
            )
            Icon(icon, null, Modifier.size(80.dp), tint = onContainer)
        }
        Text(stringResource(title), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 32.dp))
        Text(
            stringResource(body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
        )
        footer?.invoke()
    }
}

/** "Open source · github.com/…": understated, but a full-size touch target and clearly a link. */
@Composable
private fun OpenSourceLink(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val address = ProjectLinks.display(ProjectLinks.REPO)
    val description = stringResource(R.string.onboarding_open_source_cd, address)
    val label = stringResource(R.string.onboarding_open_source, address)
    val text = buildAnnotatedString {
        append(label)
        val start = label.indexOf(address)
        if (start >= 0) {
            addStyle(
                SpanStyle(color = scheme.primary, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline),
                start, start + address.length,
            )
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = scheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(top = 16.dp)
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.link_opens_browser), role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .semantics { contentDescription = description },
    )
}

/** The only permission that really matters for an alarm app, plus the two settings that make it dependable. */
@Composable
private fun PermissionsPage() {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    val notificationsGranted = remember(refresh) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val fullScreenGranted = remember(refresh) {
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.onboarding_permissions_title), style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.onboarding_permissions_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
        )
        PermissionRow(
            icon = Icons.Outlined.Notifications, title = R.string.onboarding_perm_notifications, body = R.string.onboarding_perm_notifications_body,
            granted = notificationsGranted,
            action = { request.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
        Spacer(Modifier.height(8.dp))
        PermissionRow(
            icon = Icons.Outlined.Fullscreen, title = R.string.onboarding_perm_fullscreen, body = R.string.onboarding_perm_fullscreen_body,
            granted = fullScreenGranted,
            action = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        PermissionRow(
            icon = Icons.Outlined.BatteryChargingFull, title = R.string.onboarding_perm_battery, body = R.string.onboarding_perm_battery_body,
            granted = null,
            actionLabel = R.string.onboarding_open_settings,
            action = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        )
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: Int,
    body: Int,
    granted: Boolean?,
    action: () -> Unit,
    actionLabel: Int = R.string.onboarding_allow,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = scheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            if (granted == true) {
                Icon(Icons.Outlined.CheckCircle, stringResource(R.string.onboarding_granted), tint = scheme.primary)
            } else {
                OutlinedButton(onClick = action) { Text(stringResource(actionLabel)) }
            }
        }
    }
}
