package me.danielstiner.dumble.mumble.voice.latency

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.danielstiner.dumble.mumble.voice.FRAME_NUMBER_SAMPLES
import me.danielstiner.dumble.mumble.voice.SAMPLE_RATE
import me.danielstiner.dumble.mumble.voice.TRANSMIT_BITRATE
import me.danielstiner.dumble.mumble.voice.TRANSMIT_PACKET_INTERVAL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Ends a check with [message] as its reason. Not a CancellationException: thrown by a watcher,
 *  it has to cancel the steps rather than end quietly. */
internal class CheckFailure(override val message: String) : Exception(message)

/** What capture produced while its gate was open, and where the probe is in it. */
internal data class Heard(val captured: List<CapturedPacket>, val located: Located?)

/**
 * The probe's trip on a check's session: a warm spurt and then the probe spurt go to the server's
 * loopback, the return plays through the speaker, and what capture hears is searched for it.
 *
 * - t0: the packet carrying the probe's onset is sent.
 * - t1: it comes back under our own session, by frame number; t1 − t0 is the network round trip.
 * - t2: the onset leaves capture, found by decoding capture's packets; t2 − t1 is the phone loop.
 *
 * Capture's gate opens with the probe spurt and closes 1 s after its last packet returns, 2 s after
 * that packet was sent at the latest.
 *
 * Made before the session, which is connected with [onCaptured] and [onArrived] as its callbacks.
 */
internal class ProbeExchange(
    private val probe: ProbeCodec,
    private val waitUntil: suspend (ComparableTimeMark) -> Unit = ::delayUntil,
) {
    /** t0; null until the onset packet is sent. */
    var sent: ComparableTimeMark? = null
        private set

    /** t1's packet: our own session's first return of the onset frame, with what playout held then. */
    val returned: ArrivedPacket? get() = onsetReturn.value

    var codecDelaySamples = 0
        private set

    /** Everything capture has produced so far. */
    val captured: List<CapturedPacket> get() = heard.toList()

    // Filled by the session's two callbacks, on the capture pump's and the transport's threads.
    private val heard = ConcurrentLinkedQueue<CapturedPacket>()
    private val onsetReturn = MutableStateFlow<ArrivedPacket?>(null)
    private val lastReturn = MutableStateFlow<ComparableTimeMark?>(null)

    // Ours and the spurt's last frame, known only once connected and encoded: no packet of ours
    // can arrive before both are set.
    private val session = AtomicLong(-1)
    private val lastFrame = AtomicLong(-1)

    fun onCaptured(packet: CapturedPacket) {
        heard += packet
    }

    fun onArrived(packet: ArrivedPacket) {
        if (packet.session.toLong() != session.get()) return
        if (packet.frameNumber == ONSET_FRAME) onsetReturn.compareAndSet(null, packet)
        if (packet.frameNumber == lastFrame.get()) lastReturn.compareAndSet(null, packet.at)
    }

    /**
     * Sends both spurts on [check], whose capture is open, and listens for the probe; [session] is
     * ours on the server and [atOnset] runs as t0 is marked. Call it off the main thread: it paces
     * sends on deadlines and decodes capture in [ProbeCodec.locate].
     */
    suspend fun run(check: CheckSession, session: Int, progress: (String) -> Unit = {}, atOnset: () -> Unit = {}): Heard {
        val warm = probe.encodeProbeSpurt(0, WARM_LEAD, ZERO, TRANSMIT_BITRATE)
        val spurt = probe.encodeProbeSpurt(PROBE_PEAK, PROBE_LEAD, PROBE_TAIL, TRANSMIT_BITRATE)
        codecDelaySamples = probe.codecDelaySamples(TRANSMIT_BITRATE)
        if (warm.isEmpty() || spurt.size <= ONSET_PACKET || codecDelaySamples < 0) {
            throw CheckFailure("the probe could not be encoded")
        }
        val last = SPURT_START + TRANSMIT_PACKET_INTERVAL * spurt.lastIndex
        lastFrame.set(frameAt(last))
        this.session.set(session.toLong())

        progress("sending the probe")
        val epoch = check.clock.markNow()
        suspend fun send(packets: List<ByteArray>, start: Duration, before: (Int) -> Unit = {}) {
            for ((i, packet) in packets.withIndex()) {
                val at = start + TRANSMIT_PACKET_INTERVAL * i
                waitUntil(epoch + at)
                before(i)
                check.inject(frameAt(at), packet, terminator = i == packets.lastIndex)
            }
        }
        send(warm, ZERO)
        send(spurt, SPURT_START) { m ->
            if (m == 0) check.setGateOpen(true)
            if (m == ONSET_PACKET) {
                sent = check.clock.markNow()
                atOnset()
            }
        }

        progress("listening for the probe")
        val cap = epoch + last + RETURN_TIMEOUT
        val back = withTimeoutOrNull(-cap.elapsedNow()) { lastReturn.filterNotNull().first() }
        if (back != null) waitUntil(minOf(back + LISTEN_AFTER_RETURN, cap))
        check.setGateOpen(false)
        if (returned == null) throw CheckFailure("the probe did not come back from the server")
        val packets = captured
        return Heard(packets, probe.locate(packets, codecDelaySamples))
    }

    private companion object {
        const val PROBE_PEAK = 1640
        /** Plus the 250 ms probe, sent silent (LatencyProbe.h): the 1 s warm spurt. */
        val WARM_LEAD = 750.milliseconds
        val PROBE_LEAD = 200.milliseconds
        val PROBE_TAIL = 200.milliseconds
        val SPURT_START = 2.seconds
        val RETURN_TIMEOUT = 2.seconds
        val LISTEN_AFTER_RETURN = 1.seconds

        /** The packet carrying the probe's onset, which marks t0. */
        val ONSET_PACKET = (PROBE_LEAD / TRANSMIT_PACKET_INTERVAL).toInt()
            .also { require(TRANSMIT_PACKET_INTERVAL * it == PROBE_LEAD) { "PROBE_LEAD must be whole packets" } }
        val ONSET_FRAME = frameAt(SPURT_START + TRANSMIT_PACKET_INTERVAL * ONSET_PACKET)

        // A wall clock, as the desktop client's: the gap between the spurts advances it, so the
        // playout's jitter estimator rebases on the probe spurt rather than reading it as a second late.
        fun frameAt(at: Duration): Long = (at / 1.seconds * SAMPLE_RATE / FRAME_NUMBER_SAMPLES).roundToLong()
    }
}

/** The check's pacing: [delay] until [deadline], read fresh, so a late send never delays the next. */
internal suspend fun delayUntil(deadline: ComparableTimeMark) = delay(-deadline.elapsedNow())
