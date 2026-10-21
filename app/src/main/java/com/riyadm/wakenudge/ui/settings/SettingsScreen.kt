package com.riyadm.wakenudge.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.riyadm.wakenudge.BuildConfig
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.ui.components.NavRow
import com.riyadm.wakenudge.ui.components.SectionTitle
import com.riyadm.wakenudge.ui.components.SettingsGroup
import com.riyadm.wakenudge.ui.util.Formatters
import java.time.LocalDate

class SettingsNav(
    val back: () -> Unit,
    val defaults: () -> Unit,
    val groups: () -> Unit,
    val exceptions: () -> Unit,
    val sleep: () -> Unit,
    val health: () -> Unit,
    val backup: () -> Unit,
    val about: () -> Unit,
    val appearance: () -> Unit,
    val ringing: () -> Unit,
    val audio: () -> Unit,
    val timers: () -> Unit,
)

/**
 * The settings home: a short, grouped list of places rather than one long page of switches.
 * Alarms (what rings and when), Ringing and sound (how it behaves), Look and feel, Reliability and data, About.
 */
@Composable
fun SettingsScreen(nav: SettingsNav, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back)) } },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                SectionTitle(stringResource(R.string.settings_group_alarms))
                SettingsGroup {
                    item { shape ->
                        NavRow(stringResource(R.string.settings_defaults), shape, nav.defaults, summary = stringResource(R.string.settings_defaults_hint), icon = Icons.Outlined.Tune)
                    }
                    item { shape ->
                        NavRow(stringResource(R.string.settings_groups), shape, nav.groups, summary = stringResource(R.string.settings_groups_hint), icon = Icons.Outlined.FolderSpecial)
                    }
                    item { shape ->
                        val vacation = s.vacation
                        val summary = when {
                            !vacation.enabled -> stringResource(R.string.settings_vacation_holidays_hint)
                            vacation.resumeEpochDay != null -> stringResource(R.string.settings_vacation_until, Formatters.dateShort(LocalDate.ofEpochDay(vacation.resumeEpochDay)))
                            else -> stringResource(R.string.settings_vacation_on)
                        }
                        NavRow(stringResource(R.string.settings_vacation_holidays), shape, nav.exceptions, summary = summary, icon = Icons.Outlined.BeachAccess)
                    }
                    item { shape ->
                        NavRow(stringResource(R.string.settings_sleep), shape, nav.sleep, summary = stringResource(R.string.settings_sleep_hint), icon = Icons.Outlined.Bedtime)
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.settings_group_ringing))
                SettingsGroup {
                    item { shape ->
                        NavRow(stringResource(R.string.settings_ringing), shape, nav.ringing, summary = stringResource(R.string.settings_ringing_hint), icon = Icons.Outlined.NotificationsActive)
                    }
                    item { shape ->
                        NavRow(stringResource(R.string.settings_audio_page), shape, nav.audio, summary = stringResource(R.string.settings_audio_hint), icon = Icons.Outlined.Headphones)
                    }
                    item { shape ->
                        NavRow(stringResource(R.string.settings_timers), shape, nav.timers, summary = stringResource(R.string.settings_timers_hint), icon = Icons.Outlined.Timer)
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.settings_group_look))
                SettingsGroup {
                    item { shape ->
                        val summary = stringResource(R.string.settings_appearance_summary, stringResource(themeLabel(s.themeMode)), stringResource(timeFormatLabel(s.timeFormat)))
                        NavRow(stringResource(R.string.settings_appearance_page), shape, nav.appearance, summary = summary, icon = Icons.Outlined.Palette)
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.settings_data))
                SettingsGroup {
                    item { shape ->
                        NavRow(stringResource(R.string.settings_health), shape, nav.health, summary = stringResource(R.string.settings_health_hint), icon = Icons.Outlined.MonitorHeart)
                    }
                    item { shape ->
                        NavRow(stringResource(R.string.settings_backup), shape, nav.backup, summary = stringResource(R.string.settings_backup_hint), icon = Icons.Outlined.Backup)
                    }
                }
            }
            item {
                SectionTitle(stringResource(R.string.settings_group_about))
                val context = LocalContext.current
                SettingsGroup {
                    item { shape ->
                        NavRow(stringResource(R.string.settings_about), shape, nav.about, summary = stringResource(R.string.settings_about_hint), icon = Icons.Outlined.Info)
                    }
                    item { shape ->
                        NavRow(
                            stringResource(R.string.settings_source), shape, onClick = { openInBrowser(context, BuildConfig.REPO_URL) },
                            summary = BuildConfig.REPO_URL.removePrefix("https://"), icon = Icons.Outlined.Code,
                        )
                    }
                }
            }
        }
    }
}

/** Opens a web page in the user's browser; a phone without one simply does nothing instead of crashing. */
private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: nothing sensible to do.
    }
}
