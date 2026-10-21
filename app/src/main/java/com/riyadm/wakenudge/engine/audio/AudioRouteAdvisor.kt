package com.riyadm.wakenudge.engine.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Looks at what the alarm could be played through, for routing decisions and for Alarm Health. */
@Singleton
class AudioRouteAdvisor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun outputs(): List<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    fun builtInSpeaker(): AudioDeviceInfo? = outputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** Any device worn in or on the ear: the loud-alarm cap applies to these. */
    fun headsetConnected(): Boolean = outputs().any { isHeadset(it.type) }

    fun connectedHeadsetNames(): List<String> =
        outputs().filter { isHeadset(it.type) }.map { it.productName?.toString().orEmpty().ifBlank { typeName(it.type) } }.distinct()

    private fun isHeadset(type: Int): Boolean = type in HEADSET_TYPES

    private fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        else -> "Headset"
    }

    private companion object {
        val HEADSET_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
