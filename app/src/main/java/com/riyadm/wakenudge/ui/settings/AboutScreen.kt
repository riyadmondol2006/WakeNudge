package com.riyadm.wakenudge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AlarmOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.riyadm.wakenudge.BuildConfig
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.components.SettingRow
import com.riyadm.wakenudge.ui.components.SettingsGroup
import com.riyadm.wakenudge.ui.theme.Blobs
import com.riyadm.wakenudge.ui.theme.PolygonShape

private val Libraries = listOf(
    "AndroidX Core, Activity, Lifecycle, Navigation, DataStore, Room" to "Apache-2.0",
    "Jetpack Compose and Material 3" to "Apache-2.0",
    "AndroidX Graphics Shapes" to "Apache-2.0",
    "AndroidX CameraX" to "Apache-2.0",
    "AndroidX Glance" to "Apache-2.0",
    "Dagger / Hilt" to "Apache-2.0",
    "Kotlin, kotlinx.coroutines, kotlinx.serialization" to "Apache-2.0",
    "ZXing core (barcode decoding)" to "Apache-2.0",
)

@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(128.dp).clip(PolygonShape(Blobs.Sunny)).background(scheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.AlarmOn, null, Modifier.size(56.dp), tint = scheme.onPrimaryContainer)
                    }
                    Text(stringResource(R.string.about_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.about_tagline), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
                    )
                }
            }
            item {
                SectionTitle(stringResource(R.string.about_privacy))
                SettingsGroup {
                    item { shape -> SettingRow(title = stringResource(R.string.about_offline), subtitle = stringResource(R.string.about_offline_body), shape = shape) }
                    item { shape -> SettingRow(title = stringResource(R.string.about_no_tracking), subtitle = stringResource(R.string.about_no_tracking_body), shape = shape) }
                    item { shape -> SettingRow(title = stringResource(R.string.about_permissions), subtitle = stringResource(R.string.about_permissions_body), shape = shape) }
                }
            }
            item {
                SectionTitle(stringResource(R.string.about_license))
                SettingsGroup {
                    item { shape -> SettingRow(title = stringResource(R.string.about_license_name), subtitle = stringResource(R.string.about_license_body), shape = shape) }
                }
            }
            item {
                SectionTitle(stringResource(R.string.about_libraries))
                SettingsGroup {
                    Libraries.forEach { (name, license) ->
                        item { shape -> SettingRow(title = name, subtitle = license, shape = shape) }
                    }
                }
            }
        }
    }
}
