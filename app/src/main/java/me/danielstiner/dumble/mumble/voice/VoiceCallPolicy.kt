package me.danielstiner.dumble.mumble.voice

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder

/**
 * Held while the phone owns the audio (any mode but idle and ours) or another app captures voice.
 * Which of two voice captures keeps the microphone differs between devices, measured both ways, so
 * we yield to either.
 */
internal fun isHeld(mode: Int, otherVoiceCaptures: Int) =
    (mode != AudioManager.MODE_NORMAL && mode != AudioManager.MODE_IN_COMMUNICATION) || otherVoiceCaptures > 0

/**
 * Voice captures that aren't ours. The platform strips each recording's owner but keeps its session
 * id, so ours is told apart by [ourSession]; without one, none count, since ours would.
 */
internal fun otherVoiceCaptures(configs: List<AudioRecordingConfiguration>, ourSession: Int): Int =
    if (ourSession == CaptureSessionId.NONE) 0
    else configs.count {
        it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION &&
            it.clientAudioSessionId != ourSession
    }

private val AUTO_PICKED = listOf(AudioRoute.Type.BLUETOOTH, AudioRoute.Type.WIRED_HEADSET, AudioRoute.Type.EARPIECE)

/**
 * Bluetooth, then wired, then earpiece, as a phone call picks. Never the speaker: that's the user's
 * pick. With no earpiece either, nothing is picked and the mode's default applies.
 */
internal fun preferredRoute(routes: Collection<AudioRoute>): AudioRoute? =
    routes.filter { it.type in AUTO_PICKED }.minByOrNull { AUTO_PICKED.indexOf(it.type) }

/** A headset in [now] that [before] lacked; it takes the route, as for a phone call. */
internal fun arrivedHeadset(before: Set<String>, now: Collection<AudioRoute>): AudioRoute? =
    preferredRoute(now.filter { it.id !in before && it.type != AudioRoute.Type.EARPIECE })

/** Grouped as phone calls group them: USB-C adapters are wired, LE Audio speakers Bluetooth. */
internal fun AudioDeviceInfo.toAudioRoute() = AudioRoute(
    id = id.toString(),
    type = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_HEARING_AID,
            -> AudioRoute.Type.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
            -> AudioRoute.Type.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.Type.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.Type.EARPIECE
        else -> AudioRoute.Type.UNKNOWN
    },
    name = productName?.toString().orEmpty(),
)
