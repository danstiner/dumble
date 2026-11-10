package me.danielstiner.dumble.mumble.voice.latency

import android.media.audiofx.AudioEffect
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioEffect

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CaptureEffectsTest {
    private val session = 7

    @After fun reset() = ShadowAudioEffect.reset()

    private fun register(type: java.util.UUID) = ShadowAudioEffect.addEffect(
        AudioEffect.Descriptor(type.toString(), java.util.UUID.randomUUID().toString(), "Pre Processing", "fx", "test"),
    )

    @Test fun withNoFrameworkEffectsNothingIsFound() {
        val effects = CaptureEffects.disable(session)
        assertEquals(emptyList<String>(), effects.names)
        effects.restore()
    }

    @Test fun bothEffectsAreFoundInOrder() {
        register(AudioEffect.EFFECT_TYPE_AEC)
        register(AudioEffect.EFFECT_TYPE_NS)
        val effects = CaptureEffects.disable(session)
        try {
            assertEquals(listOf(CaptureEffects.ECHO_CANCELLER, CaptureEffects.NOISE_SUPPRESSOR), effects.names)
        } finally {
            effects.restore()
        }
    }

    @Test fun restoreReleasesTheEffectsItHeld() {
        register(AudioEffect.EFFECT_TYPE_AEC)
        CaptureEffects.disable(session).restore()
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }
}
