package io.github.xiaoxin2016.videocall

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Puts audio into communication mode; speaker unless a headset is connected. */
class AudioRouter(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val savedMode = am.mode

    @Suppress("DEPRECATION")
    private val savedSpeaker = am.isSpeakerphoneOn

    fun start() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = am.availableCommunicationDevices
            val preferred = listOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            )
            preferred.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
                ?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = !am.isWiredHeadsetOn && !am.isBluetoothScoOn
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = savedSpeaker
        }
        am.mode = savedMode
    }
}
