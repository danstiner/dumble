package me.danielstiner.dumble.session

import me.danielstiner.dumble.mumble.net.CryptState
import me.danielstiner.dumble.mumble.net.MumbleControlTransport
import me.danielstiner.dumble.mumble.net.MumbleUdpTransport
import me.danielstiner.dumble.mumble.net.NetworkWatch
import me.danielstiner.dumble.mumble.net.PinStore
import me.danielstiner.dumble.mumble.net.UdpTransport
import me.danielstiner.dumble.mumble.voice.VoiceCall
import me.danielstiner.dumble.mumble.voice.VoiceReceiver
import me.danielstiner.dumble.mumble.voice.VoiceSender
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * What a session is built from, in one value so a second owner can build a session from the same
 * wiring.
 */
internal class SessionParts(
    val pinStore: PinStore,
    val newTransport: (expectedPin: String?) -> MumbleControlTransport,
    val newUdp: (CryptState, MumbleUdpTransport.Listener) -> UdpTransport,
    val newPlayout: () -> VoiceReceiver.PlayoutHandle?,
    /** Builds a session's native capture engine; null when the device refuses. */
    val newCapture: () -> VoiceSender.CaptureHandle?,
    val call: VoiceCall,
    /** The one microphone owner every session shares. */
    val captures: CaptureLifecycle,
    /** The clock of everything that must count sleep. */
    val bootClock: TimeSource.WithComparableMarks,
    val pingIntervalMs: Long,
    val sleep: suspend (Duration) -> Unit,
    val networkWatch: NetworkWatch,
)
