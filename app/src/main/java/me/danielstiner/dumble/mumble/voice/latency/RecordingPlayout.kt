package me.danielstiner.dumble.mumble.voice.latency

import android.util.Log
import me.danielstiner.dumble.mumble.voice.NativePlayout
import me.danielstiner.dumble.mumble.voice.VoiceReceiver
import kotlin.time.TimeSource

/**
 * [engine], also reporting each packet it accepts to a latency check: when the packet reached it
 * and what it held for the sender before it. A check's session builds its receiver on this, so the
 * receiver itself knows nothing of checks.
 */
class RecordingPlayout(
    private val engine: VoiceReceiver.PlayoutEngine,
    private val clock: TimeSource.WithComparableMarks,
    private val arrived: (ArrivedPacket) -> Unit,
) : VoiceReceiver.PlayoutEngine by engine {
    private var failed = false

    override fun offer(session: Int, opusData: ByteArray, frameNumber: Long, terminator: Boolean): VoiceReceiver.Offered {
        val at = clock.markNow()
        val offered = engine.offer(session, opusData, frameNumber, terminator)
        if (offered.code == NativePlayout.OFFER_ACCEPTED) {
            try {
                arrived(ArrivedPacket(
                    session, frameNumber, at, offered.aheadSamples, offered.targetSamples, offered.writeAheadSamples,
                ))
            } catch (t: Throwable) {
                // This runs on the transport's reader, which a check's bug must not end. Logged
                // once: packets come fifty a second.
                if (!failed) Log.e(TAG, "arrival record failed", t)
                failed = true
            }
        }
        return offered
    }

    private companion object {
        const val TAG = "RecordingPlayout"
    }
}
