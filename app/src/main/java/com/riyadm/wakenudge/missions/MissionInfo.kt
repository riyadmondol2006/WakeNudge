package com.riyadm.wakenudge.missions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.nfc.NfcAdapter
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.ui.graphics.vector.ImageVector
import com.riyadm.wakenudge.R
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.missions.ui.hasSensor

/** Extra keys stored in [com.riyadm.wakenudge.domain.model.MissionConfig.params] by this module. */
object MissionParams {
    /** Tiny base64 JPEG (at most 3 KB) of the reference photo, so the alarm editor can show what to photograph. */
    const val THUMB = "thumb"
}

/** Names, icons and device requirements of the wake-up missions, for the alarm editor and the ring screen. */
object MissionInfo {
    @StringRes
    fun titleRes(type: MissionType): Int = when (type) {
        MissionType.MATH -> R.string.mission_title_math
        MissionType.MEMORY -> R.string.mission_title_memory
        MissionType.TYPING -> R.string.mission_title_typing
        MissionType.QR_BARCODE -> R.string.mission_title_qr
        MissionType.NFC -> R.string.mission_title_nfc
        MissionType.SHAKE -> R.string.mission_title_shake
        MissionType.STEPS -> R.string.mission_title_steps
        MissionType.SQUATS -> R.string.mission_title_squats
        MissionType.PUZZLE -> R.string.mission_title_puzzle
        MissionType.PHOTO -> R.string.mission_title_photo
        MissionType.FOCUS_TAP -> R.string.mission_title_focus
    }

    @StringRes
    fun descriptionRes(type: MissionType): Int = when (type) {
        MissionType.MATH -> R.string.mission_desc_math
        MissionType.MEMORY -> R.string.mission_desc_memory
        MissionType.TYPING -> R.string.mission_desc_typing
        MissionType.QR_BARCODE -> R.string.mission_desc_qr
        MissionType.NFC -> R.string.mission_desc_nfc
        MissionType.SHAKE -> R.string.mission_desc_shake
        MissionType.STEPS -> R.string.mission_desc_steps
        MissionType.SQUATS -> R.string.mission_desc_squats
        MissionType.PUZZLE -> R.string.mission_desc_puzzle
        MissionType.PHOTO -> R.string.mission_desc_photo
        MissionType.FOCUS_TAP -> R.string.mission_desc_focus
    }

    fun icon(type: MissionType): ImageVector = when (type) {
        MissionType.MATH -> Icons.Filled.Calculate
        MissionType.MEMORY -> Icons.Filled.Psychology
        MissionType.TYPING -> Icons.Filled.Keyboard
        MissionType.QR_BARCODE -> Icons.Filled.QrCodeScanner
        MissionType.NFC -> Icons.Filled.Nfc
        MissionType.SHAKE -> Icons.Filled.Vibration
        MissionType.STEPS -> Icons.AutoMirrored.Filled.DirectionsWalk
        MissionType.SQUATS -> Icons.Filled.FitnessCenter
        MissionType.PUZZLE -> Icons.Filled.Extension
        MissionType.PHOTO -> Icons.Filled.PhotoCamera
        MissionType.FOCUS_TAP -> Icons.Filled.TouchApp
    }

    /**
     * The runtime permission the mission cannot work without, or null. STEPS needs ACTIVITY_RECOGNITION only on
     * Android 10+ (and can still count with the motion sensor if the user declines).
     */
    fun requiredPermission(type: MissionType): String? = when (type) {
        MissionType.QR_BARCODE, MissionType.PHOTO -> Manifest.permission.CAMERA
        MissionType.STEPS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Manifest.permission.ACTIVITY_RECOGNITION else null
        else -> null
    }

    /** Whether the phone has the hardware the mission needs; unsupported missions should not be offered or enforced. */
    fun isSupported(context: Context, type: MissionType): Boolean = when (type) {
        MissionType.MATH, MissionType.MEMORY, MissionType.TYPING, MissionType.PUZZLE, MissionType.FOCUS_TAP -> true
        MissionType.QR_BARCODE, MissionType.PHOTO -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        MissionType.NFC -> NfcAdapter.getDefaultAdapter(context) != null
        MissionType.SHAKE, MissionType.SQUATS -> context.hasSensor(Sensor.TYPE_ACCELEROMETER)
        MissionType.STEPS -> context.hasSensor(Sensor.TYPE_STEP_COUNTER) || context.hasSensor(Sensor.TYPE_STEP_DETECTOR) ||
            context.hasSensor(Sensor.TYPE_ACCELEROMETER)
    }

    /**
     * The order the mission picker lists them in. A code on something in another room is what works best for most people,
     * so it comes first; the ones that wear off or can be faked come last, each with a note saying so ([noteRes]).
     */
    val pickerOrder: List<MissionType> = listOf(
        MissionType.QR_BARCODE, MissionType.NFC, MissionType.FOCUS_TAP, MissionType.MEMORY, MissionType.PUZZLE, MissionType.TYPING,
        MissionType.MATH, MissionType.PHOTO, MissionType.STEPS, MissionType.SQUATS, MissionType.SHAKE,
    )

    /** Missions that need the phone moved with the body. */
    val PHYSICAL: Set<MissionType> = setOf(MissionType.SHAKE, MissionType.STEPS, MissionType.SQUATS)

    /** Missions that are a race against time when played on sight: shapes that come and go, a sequence that flashes. */
    val TIMED: Set<MissionType> = setOf(MissionType.FOCUS_TAP, MissionType.MEMORY)

    /** What the picker offers, with "I can't do timed or physical missions" ([noTimed]) on or off. */
    fun pickable(noTimed: Boolean): List<MissionType> =
        if (noTimed) pickerOrder.filter { it !in PHYSICAL && it !in TIMED } else pickerOrder

    /** An honest line about how well a mission keeps people up, where there is something worth saying. */
    @StringRes
    fun noteRes(type: MissionType): Int? = when (type) {
        MissionType.QR_BARCODE -> R.string.mission_note_qr
        MissionType.MATH -> R.string.mission_note_math
        MissionType.PHOTO -> R.string.mission_note_photo
        MissionType.STEPS -> R.string.mission_note_steps
        else -> null
    }

    @StringRes
    fun unsupportedReasonRes(type: MissionType): Int = when (type) {
        MissionType.QR_BARCODE, MissionType.PHOTO -> R.string.mission_unsupported_camera
        MissionType.NFC -> R.string.mission_unsupported_nfc
        MissionType.SHAKE, MissionType.SQUATS, MissionType.STEPS -> R.string.mission_unsupported_motion
        else -> R.string.mission_unsupported_generic
    }
}
