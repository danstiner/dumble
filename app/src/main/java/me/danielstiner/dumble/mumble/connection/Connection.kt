package me.danielstiner.dumble.mumble.connection

import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.ComparableTimeMark
import me.danielstiner.dumble.mumble.channeltree.ChannelTree
import me.danielstiner.dumble.mumble.chat.ChatMessage
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.net.VoicePath
import me.danielstiner.dumble.mumble.protocol.ServerVersion
import me.danielstiner.dumble.mumble.protocol.UserStats
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.CaptureStats
import me.danielstiner.dumble.mumble.voice.PlayoutStats
import me.danielstiner.dumble.mumble.voice.TransmitMode

/** The coordinator surface the UI depends on — narrow so the ViewModel can be tested with a fake. */
interface Connection {
    val status: StateFlow<ConnectionStatus>
    val serverVersion: StateFlow<ServerVersion?>
    val roundTripTime: StateFlow<Duration?>

    /** Which transport carries our own voice and, on UDP, the round trip that proved it. The
     *  fresh state, tunneled, while disconnected. */
    val voicePath: StateFlow<VoicePath.State>

    /**
     * When the server last said anything — seeded at ServerSync, then advanced by each ping reply;
     * null while disconnected. The UI ages it against `SessionStateMachine.DEGRADED_PING_AGE`.
     *
     * The instant rather than the age, because the age is stale the moment it is published and
     * what changes it is the passage of time; the UI derives it against its own clock.
     */
    val lastServerReplyAt: StateFlow<ComparableTimeMark?>
    val channelTree: StateFlow<ChannelTree>
    val messages: StateFlow<List<ChatMessage>>
    val speakingSessions: StateFlow<Set<Int>>

    /** Our own audio reaching the wire, held ~200 ms past the last packet so it does not strobe
     *  between words. [speakingSessions] never contains us: it is built from decoded incoming
     *  audio. */
    val selfSpeaking: StateFlow<Boolean>

    /** A cellular call has taken the microphone. Capture is released while this is true;
     *  [requestCapture] asks for both back. */
    val callHeld: StateFlow<Boolean>

    /** The receive path's last second of measurement; null before the first one lands. */
    val playoutStats: StateFlow<PlayoutStats?>

    /** Our own send path's counters, read from the pump every two seconds; null without a
     *  capture session, so the sheet's rows read as absent rather than as zero. */
    val captureStats: StateFlow<CaptureStats?>

    /**
     * The last per-user ping the server answered with, or null before any. One slot rather than a
     * map: only one user's stats are ever asked for at a time, and the record names its own
     * session so a late reply cannot be read under the wrong name.
     */
    val userStats: StateFlow<UserStats?>

    /**
     * What the platform offers for call audio and what it is using. Cleared to empty by connect()
     * and disconnect() — but not by a session that fails on its own: retiring it deliberately
     * leaves every published flow, this one included, holding the dead session's last values until
     * the next connect() clears them, the same as `status` staying on its terminal `Error`.
     */
    val audioRoutes: StateFlow<AudioRoutes>
    fun connect(endpoint: MumbleEndpoint, username: String, password: String?)
    fun trustAndConnect()
    fun cancelTrust()
    fun disconnect()
    fun sendText(text: String): Boolean

    /**
     * Raise "a capture session is wanted" on the live connection — a level, not an open. The
     * session is built only while the connection is live and the platform is not holding the call,
     * and it is rebuilt whenever that becomes true again. Call with RECORD_AUDIO granted; safe to
     * repeat, a no-op with nothing connected.
     */
    fun requestCapture()

    /**
     * Push-to-talk. Opening the gate also asks for capture, exactly as [requestCapture] does, so a
     * press recovers a session a terminal engine failure or a hold took away — but asynchronously,
     * so the press that rebuilds is not the press that transmits. The intent is remembered either
     * way: a session built while the button is still down comes up transmitting.
     */
    fun setTransmitting(on: Boolean)

    /**
     * Deafen or undeafen. Enforcement is entirely the server's — no playback path reads `self_deaf`;
     * murmur stops sending to a deaf receiver, so this saves bandwidth rather than muting locally.
     *
     * Fire-and-forget — nothing local reads back, because the server broadcasts the resulting
     * `UserState` to us like any other user's and the channel tree is what the UI renders. A no-op
     * until synchronized, and safe to repeat: a repeat re-sends the last intent rather than
     * recomputing it.
     */
    fun setSelfDeaf(on: Boolean)

    /**
     * Mute or unmute. Sends `self_mute` and closes the transmit gate locally, so a voice-activity
     * microphone goes silent at the tap rather than at the server's echo. Fire-and-forget; the
     * wire half is a no-op until synchronized.
     */
    fun setMuted(on: Boolean)

    /**
     * How the microphone decides to transmit. A setting rather than session state: it outlives
     * connections and is applied to every capture session. Switching to push-to-talk lifts a
     * self-mute, since that mode has no control to lift it. Fire-and-forget.
     */
    fun setTransmitMode(mode: TransmitMode)

    /**
     * Route call audio to [routeId], one of [audioRoutes]' available ids. Fire-and-forget, and a
     * no-op with nothing connected: the platform's answer arrives back through [audioRoutes], so
     * the control never shows a route the platform has not confirmed.
     */
    fun requestAudioRoute(routeId: String)

    /**
     * Ask the server for one user's ping; the answer arrives on [userStats]. Fire-and-forget, a
     * no-op with nothing connected, and safe to repeat — the UI repeats it while a sheet is open.
     */
    fun requestUserStats(session: Int)
}
