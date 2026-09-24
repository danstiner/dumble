package me.danielstiner.dumble.mumble.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.danielstiner.dumble.mumble.voice.AudioRoute.Type
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoiceCallPolicyAndroidTest {
    private val audio = ApplicationProvider.getApplicationContext<Context>()
        .getSystemService(AudioManager::class.java)

    private fun recording(session: Int, source: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION) =
        shadowOf(audio).createActiveRecordingConfiguration(session, source, "")

    @Test fun aHeadsetKeepsItsIdAndName() {
        assertEquals(
            AudioRoute("7", Type.BLUETOOTH, "WH-1000XM5"),
            audioDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 7, "WH-1000XM5").toAudioRoute(),
        )
    }

    @Test fun eachDeviceTypeMapsToItsRouteType() {
        for ((type, expected) in listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET to Type.BLUETOOTH,
            AudioDeviceInfo.TYPE_BLE_SPEAKER to Type.BLUETOOTH,
            AudioDeviceInfo.TYPE_HEARING_AID to Type.BLUETOOTH,
            AudioDeviceInfo.TYPE_WIRED_HEADSET to Type.WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to Type.WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET to Type.WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE to Type.WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to Type.SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to Type.EARPIECE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to Type.UNKNOWN,
        )) {
            assertEquals("type $type", expected, audioDevice(type, 1).toAudioRoute().type)
        }
    }

    @Test fun ourOwnCaptureIsNotAnotherApps() {
        assertEquals(0, otherVoiceCaptures(listOf(recording(OURS)), OURS))
        assertEquals(1, otherVoiceCaptures(listOf(recording(OURS), recording(99)), OURS))
    }

    /** Only a voice capture is a call. */
    @Test fun otherSourcesDoNotCount() {
        assertEquals(0, otherVoiceCaptures(listOf(recording(99, MediaRecorder.AudioSource.MIC)), OURS))
    }

    /** Without our own id ours cannot be told apart, and counting it would hold us for good. */
    @Test fun withoutOurIdNothingCounts() {
        assertEquals(0, otherVoiceCaptures(listOf(recording(99)), CaptureSessionId.NONE))
    }

    private companion object {
        const val OURS = 41
    }
}
