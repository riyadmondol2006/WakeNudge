package com.riyadm.wakenudge.missions

import android.content.Context
import android.nfc.NfcAdapter
import com.riyadm.wakenudge.domain.model.MissionType
import com.riyadm.wakenudge.engine.MissionReadiness
import com.riyadm.wakenudge.missions.ui.isPermissionGranted
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Asks the phone whether a kind of mission can run right now. Read-only, and it never throws: a phone that will not say counts as fine. */
@Singleton
class MissionProbe @Inject constructor(@ApplicationContext private val context: Context) {
    fun readiness(type: MissionType): MissionReadiness = runCatching {
        MissionReadiness(
            // Steps can count with the motion sensor without its permission, so only the camera really needs one.
            permissionMissing = type != MissionType.STEPS &&
                MissionInfo.requiredPermission(type)?.let { !isPermissionGranted(context, it) } == true,
            nfcOff = type == MissionType.NFC && NfcAdapter.getDefaultAdapter(context)?.isEnabled == false,
            hardwareMissing = !MissionInfo.isSupported(context, type),
        )
    }.getOrDefault(MissionReadiness())
}
