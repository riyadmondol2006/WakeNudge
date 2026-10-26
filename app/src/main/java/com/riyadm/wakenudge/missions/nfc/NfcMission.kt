package com.riyadm.wakenudge.missions.nfc

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.MissionConfig
import com.riyadm.wakenudge.missions.ui.MissionButton
import com.riyadm.wakenudge.missions.ui.MissionButtonKind
import com.riyadm.wakenudge.missions.ui.MissionNotice
import com.riyadm.wakenudge.missions.ui.MissionProgress
import com.riyadm.wakenudge.missions.ui.MissionPrompt
import com.riyadm.wakenudge.missions.ui.MissionScaffold
import com.riyadm.wakenudge.missions.ui.PulsingBadge
import com.riyadm.wakenudge.missions.ui.SetupFrame
import com.riyadm.wakenudge.missions.ui.errorShake
import com.riyadm.wakenudge.missions.ui.findActivity
import com.riyadm.wakenudge.missions.ui.rememberErrorFeedback
import com.riyadm.wakenudge.missions.ui.MissionWayOut
import com.riyadm.wakenudge.missions.ui.rememberResumeCount
import com.riyadm.wakenudge.ui.theme.LocalWakeHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
    NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

/**
 * Receives NFC tags while the screen is resumed and [enabled]. Reader mode gives this screen exclusive access to tags,
 * so no other app is launched by the tap and no sound plays; it is switched off the moment the screen pauses or leaves
 * the composition. [onTag] runs on the main thread.
 */
@Composable
private fun NfcReaderEffect(adapter: NfcAdapter?, enabled: Boolean, onTag: (Tag) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onTag)
    DisposableEffect(adapter, activity, enabled, lifecycleOwner) {
        if (adapter == null || activity == null || !enabled) return@DisposableEffect onDispose { }
        var active = false
        fun enable() {
            if (active) return
            active = runCatching {
                adapter.enableReaderMode(activity, { tag -> scope.launch { latest(tag) } }, READER_FLAGS, null)
            }.isSuccess
        }
        fun disable() {
            if (!active) return
            runCatching { adapter.disableReaderMode(activity) }
            active = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> enable()
                Lifecycle.Event.ON_PAUSE -> disable()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            disable()
        }
    }
}

/** Whether NFC is currently switched on, updated live when the user toggles it in system settings. */
@Composable
private fun rememberNfcEnabled(adapter: NfcAdapter?): Boolean {
    val context = LocalContext.current
    val resumeCount = rememberResumeCount()
    var enabled by remember { mutableStateOf(isNfcOn(adapter)) }
    LaunchedEffect(adapter, resumeCount) { enabled = isNfcOn(adapter) }
    DisposableEffect(adapter) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                enabled = isNfcOn(adapter)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return enabled
}

/** `isEnabled` talks to the NFC system service, which can be dead on a few phones; treat that as off. */
private fun isNfcOn(adapter: NfcAdapter?): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

private fun openNfcSettings(context: Context) {
    val intent = Intent(Settings.ACTION_NFC_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(intent) }.isFailure) {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * The "NFC is off" state shared by the mission and its setup. During a ring, someone who went to the settings and came
 * back with NFC still off (a locked phone that would not open them, a setting they cannot find) is offered Continue,
 * like a refused camera permission: a tag that cannot be read must not trap them.
 */
@Composable
private fun NfcOffNotice() {
    val context = LocalContext.current
    val resumes = rememberResumeCount()
    var openedAt by remember { mutableIntStateOf(-1) }
    MissionNotice(
        icon = Icons.Filled.Nfc,
        title = stringResource(R.string.mission_nfc_off_title),
        body = stringResource(R.string.mission_nfc_off_body),
    ) {
        MissionButton(
            stringResource(R.string.mission_nfc_open_settings),
            { openedAt = resumes; openNfcSettings(context) },
            Modifier.fillMaxWidth(), icon = Icons.Filled.Settings,
        )
        if (openedAt >= 0 && resumes > openedAt) MissionWayOut()
    }
}

/**
 * Explains, honestly, that many phones do not read tags while locked, and offers to unlock. Shown only while the
 * keyguard is actually up. [KeyguardManager.requestDismissKeyguard] needs an Activity; without one the card is
 * informational only.
 */
@Composable
private fun LockScreenNote(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val keyguard = remember(context) { context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager }
    val resumeCount = rememberResumeCount()
    var refresh by remember { mutableIntStateOf(0) }
    val locked = remember(resumeCount, refresh) { keyguard?.isKeyguardLocked == true }
    if (!locked) return
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.mission_nfc_locked_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(stringResource(R.string.mission_nfc_locked_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        if (activity != null && keyguard != null) {
            MissionButton(
                stringResource(R.string.mission_nfc_unlock),
                {
                    keyguard.requestDismissKeyguard(
                        activity,
                        object : KeyguardManager.KeyguardDismissCallback() {
                            override fun onDismissSucceeded() { refresh++ }
                            override fun onDismissCancelled() { refresh++ }
                            override fun onDismissError() { refresh++ }
                        },
                    )
                },
                Modifier.fillMaxWidth(),
                kind = MissionButtonKind.Tonal,
                icon = Icons.Filled.LockOpen,
            )
        }
    }
}

/** Tap the registered NFC tag [MissionConfig.count] times. */
@Composable
internal fun NfcMission(config: MissionConfig, onInteraction: () -> Unit, onComplete: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalWakeHaptics.current
    val feedback = rememberErrorFeedback()
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val nfcEnabled = rememberNfcEnabled(adapter)
    val expected = config.params[MissionConfig.PARAM_TAG_ID].orEmpty()
    val total = config.count.coerceAtLeast(1)
    var tapped by rememberSaveable { mutableIntStateOf(0) }
    var finished by rememberSaveable { mutableStateOf(false) }
    var wrong by remember { mutableStateOf(false) }
    var lastResultAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(finished) {
        if (finished) {
            delay(700)
            onComplete()
        }
    }

    NfcReaderEffect(adapter, enabled = nfcEnabled && !finished) { tag ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastResultAt < COOLDOWN_MS) return@NfcReaderEffect
        lastResultAt = now
        onInteraction()
        if (NfcTagIds.matches(expected, NfcTagIds.toHex(tag.id))) {
            wrong = false
            haptics.confirm()
            tapped++
            if (tapped >= total) finished = true
        } else {
            wrong = true
            haptics.reject()
            feedback.trigger()
        }
    }

    MissionScaffold(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (total > 1) MissionProgress(tapped.toFloat() / total, stringResource(R.string.mission_nfc_progress, minOf(tapped + 1, total), total))
        if (adapter == null) {
            MissionNotice(Icons.Filled.SensorsOff, stringResource(R.string.mission_nfc_missing_title), stringResource(R.string.mission_unsupported_nfc)) {
                MissionWayOut()
            }
        } else if (!nfcEnabled) {
            NfcOffNotice()
        } else {
            MissionPrompt(stringResource(R.string.mission_nfc_prompt), body = stringResource(R.string.mission_nfc_hint))
            PulsingBadge(
                icon = if (finished) Icons.Filled.Check else Icons.Filled.Nfc,
                size = 160.dp,
                pulsing = !finished,
                modifier = Modifier.errorShake(feedback).padding(vertical = 8.dp),
                container = if (finished) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                content = if (finished) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                when {
                    finished -> stringResource(R.string.mission_nfc_found)
                    wrong -> stringResource(R.string.mission_nfc_wrong)
                    else -> stringResource(R.string.mission_nfc_waiting)
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = if (wrong && !finished) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            )
            LockScreenNote()
        }
    }
}

private const val COOLDOWN_MS = 1500L

/** One-time registration: tap the tag to use, confirm it, and store its id in the config. */
@Composable
internal fun NfcSetup(config: MissionConfig, onSaved: (MissionConfig) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalWakeHaptics.current
    val adapter = remember(context) { NfcAdapter.getDefaultAdapter(context) }
    val nfcEnabled = rememberNfcEnabled(adapter)
    var tagId by rememberSaveable { mutableStateOf<String?>(null) }
    var randomUid by rememberSaveable { mutableStateOf(false) }

    NfcReaderEffect(adapter, enabled = nfcEnabled && tagId == null) { tag ->
        val id = tag.id
        randomUid = NfcTagIds.isRandomUid(id)
        if (randomUid) haptics.reject() else haptics.confirm()
        tagId = NfcTagIds.toHex(id)
    }

    SetupFrame(stringResource(R.string.mission_setup_nfc_title), onCancel) {
        val captured = tagId
        when {
            adapter == null -> MissionNotice(Icons.Filled.SensorsOff, stringResource(R.string.mission_nfc_missing_title), stringResource(R.string.mission_unsupported_nfc))
            !nfcEnabled -> NfcOffNotice()
            captured == null -> {
                MissionPrompt(stringResource(R.string.mission_setup_nfc_prompt), body = stringResource(R.string.mission_setup_nfc_hint))
                Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    PulsingBadge(icon = Icons.Filled.Nfc, size = 160.dp)
                }
            }
            randomUid -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                MissionNotice(
                    icon = Icons.Filled.Block,
                    title = stringResource(R.string.mission_setup_nfc_random_title),
                    body = stringResource(R.string.mission_setup_nfc_random_body),
                ) {
                    MissionButton(stringResource(R.string.mission_setup_nfc_try_another), { tagId = null; randomUid = false }, Modifier.fillMaxWidth())
                }
            }
            else -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
                MissionNotice(
                    icon = Icons.Filled.Nfc,
                    title = stringResource(R.string.mission_setup_nfc_found),
                    body = stringResource(R.string.mission_setup_nfc_found_body, NfcTagIds.pretty(captured)),
                ) {
                    MissionButton(stringResource(R.string.mission_setup_nfc_use), {
                        onSaved(config.copy(params = config.params + (MissionConfig.PARAM_TAG_ID to captured)))
                    }, Modifier.fillMaxWidth())
                    MissionButton(stringResource(R.string.mission_setup_nfc_try_another), { tagId = null }, Modifier.fillMaxWidth(), kind = MissionButtonKind.Tonal)
                }
            }
        }
        if (adapter != null && nfcEnabled && captured == null) LockScreenNote()
    }
}
