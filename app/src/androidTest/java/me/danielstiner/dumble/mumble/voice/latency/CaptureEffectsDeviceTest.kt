package me.danielstiner.dumble.mumble.voice.latency

import android.Manifest
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import androidx.test.platform.app.InstrumentationRegistry
import me.danielstiner.dumble.mumble.voice.CaptureSessionId
import me.danielstiner.dumble.mumble.voice.openNativeCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** The effects a latency check needs off can be turned off on Dumble's own capture session, and
 *  come back as they were. Whether the probe is then heard needs a real speaker and mic. Skips
 *  where the framework offers neither effect (the stock emulator's `audio_effects.xml` leaves
 *  `pre_processing` commented out). */
class CaptureEffectsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun dumblesCaptureSessionsEffectsTurnOffAndComeBack() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val capture = checkNotNull(openNativeCapture(context)) { "capture did not open" }
        try {
            val session = CaptureSessionId.get(context)
            val before = state(session)
            assumeTrue("no software capture effects on this device", before.isNotEmpty())
            val effects = CaptureEffects.disable(session)
            try {
                assertEquals(before.keys.toList(), effects.names)
                assertTrue("every effect found is off", effects.allOff())
                assertTrue("a second handle on the session sees them off", state(session).values.none { it })
            } finally {
                effects.restore()
            }
            assertEquals("restored as found", before, state(session))
        } finally {
            capture.stop()
            capture.destroy()
        }
    }

    /** Each effect's state on [session], read through a handle of its own. */
    private fun state(session: Int): Map<String, Boolean> = buildMap {
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(session)?.let { put(CaptureEffects.ECHO_CANCELLER, it.enabled); it.release() }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(session)?.let { put(CaptureEffects.NOISE_SUPPRESSOR, it.enabled); it.release() }
        }
    }
}
