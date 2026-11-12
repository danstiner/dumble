package me.danielstiner.dumble.mumble.voice.latency

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.danielstiner.dumble.mumble.voice.CaptureSessionId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioEffect
import org.robolectric.util.ReflectionHelpers

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CaptureEffectsTest {
    private val session = 7

    @After fun reset() = ShadowAudioEffect.reset()

    private fun register(type: java.util.UUID) = ShadowAudioEffect.addEffect(
        AudioEffect.Descriptor(type.toString(), java.util.UUID.randomUUID().toString(), "Pre Processing", "fx", "test"),
    )

    /** An echo canceller that is on, as the platform leaves it when capture opens. */
    private fun enabledCanceller(): AudioEffect {
        register(AudioEffect.EFFECT_TYPE_AEC)
        return checkNotNull(AcousticEchoCanceler.create(session)).also { it.setEnabled(true) }
    }

    private fun shadowOf(effect: AudioEffect): ShadowAudioEffect = Shadow.extract(effect)

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

    @Test fun withNoCaptureSessionTheFrameworkIsNotAsked() {
        register(AudioEffect.EFFECT_TYPE_AEC)
        assertEquals(emptyList<String>(), CaptureEffects.disable(CaptureSessionId.NONE).names)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }

    @Test fun anEnabledEffectIsTurnedOffAndPutBack() {
        val canceller = enabledCanceller()
        val effects = CaptureEffects.hold(listOf(CaptureEffects.ECHO_CANCELLER to canceller))
        assertFalse(canceller.enabled)
        assertTrue(effects.allOff())
        assertTrue(effects.restore())
        // Released, so the effect itself can no longer be asked.
        assertTrue(ReflectionHelpers.getField(shadowOf(canceller), "isEnabled"))
    }

    @Test fun anEffectThatRefusesIsNeitherOffNorRestored() {
        val canceller = enabledCanceller()
        shadowOf(canceller).setErrorCode(AudioEffect.ERROR_INVALID_OPERATION)
        val effects = CaptureEffects.hold(listOf(CaptureEffects.ECHO_CANCELLER to canceller))
        assertFalse(effects.allOff())
        assertFalse(effects.restore())
    }

    @Test fun restoreReleasesTheEffectsItHeld() {
        register(AudioEffect.EFFECT_TYPE_AEC)
        CaptureEffects.disable(session).restore()
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }
}
