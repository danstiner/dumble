package me.danielstiner.dumble.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.mumble.voice.AudioRoute
import me.danielstiner.dumble.mumble.voice.AudioRoutes
import me.danielstiner.dumble.mumble.voice.VoiceCall
import java.util.concurrent.Executor

// Internal, not private: AudioManagerRouteTest drives this directly, the same reason
// CallEndpointCompat.toAudioRoute is internal. A swapped constant maps every device to UNKNOWN,
// which the menu still renders and still forwards taps for.
internal fun AudioDeviceInfo.toAudioRoute() = AudioRoute(
    id = id.toString(),
    type = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
            -> AudioRoute.Type.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
            -> AudioRoute.Type.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.Type.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.Type.EARPIECE
        else -> AudioRoute.Type.UNKNOWN
    },
    // Real device names arrive here with no BLUETOOTH_CONNECT lookup of ours, so the id collapse
    // RouteState dedupes against under core-telecom has no equivalent on this path.
    name = productName?.toString().orEmpty(),
)

/**
 * The spike alternative to [TelecomCall]: the same [VoiceCall] seam driven by AudioManager alone,
 * registering no platform call.
 *
 * Exists to answer whether core-telecom earns what it costs (TODO.md, "Is core-telecom worth what
 * it costs?"). An earlier spike branch measured the mechanism working and was then deleted, leaving
 * the evidence as prose; this one is meant to be kept and re-run.
 *
 * What it buys over core-telecom, both recorded from that spike:
 * - Ducking. The library exposes no control over the focus request the platform makes on its
 *   behalf, so other apps get LOSS_TRANSIENT and stop. Owning the request means asking for
 *   GAIN_TRANSIENT_MAY_DUCK, and they duck instead.
 * - A resume signal. Core-telecom delivers none — [VoiceCall.requestActive]'s KDoc records that a
 *   held session stays dead until something asks — so today a Talk press is the only retry. The
 *   mode listener reports the interruption ending.
 *
 * What it gives up: arbitration against *other* VoIP apps. Their call takes focus but not our mode,
 * and the standing rule is never to tear down capture on focus loss, so both microphones stay live.
 * That is the trade to accept or reject on the strength of this branch.
 *
 * Serialised the same way [TelecomCall] is, and deliberately: there is no async grant to race here,
 * but the seam's contract is that commands apply in send order on one consumer, and the lifecycle
 * tests drive that contract. Keeping the queue keeps them meaningful.
 */
class AudioManagerCall(context: Context) : VoiceCall {

    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))

    // Process-scoped, like TelecomCall's: the call outlives any Activity. Main so the listeners
    // and the consumer agree on a thread without a lock.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val executor = Executor { scope.launch { it.run() } }
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private sealed interface Command {
        data class Start(
            val gen: Int,
            val onActive: (Boolean) -> Unit,
            val onRoutes: (AudioRoutes) -> Unit,
            val onEnded: () -> Unit,
        ) : Command

        data class End(val gen: Int) : Command
        data class RequestActive(val gen: Int) : Command
        data class RequestRoute(val gen: Int, val routeId: String) : Command
        /** The platform's mode changed — someone else may have taken the audio, or given it back. */
        data class ModeChanged(val mode: Int) : Command
        /** The platform moved our audio, or the device list changed under it. */
        data object RoutesChanged : Command
    }

    private class LiveCall(
        val gen: Int,
        val onActive: (Boolean) -> Unit,
        val onRoutes: (AudioRoutes) -> Unit,
        val onEnded: () -> Unit,
    ) {
        /** Whether another owner (a cellular call) currently has the audio. */
        var held = false
    }

    private var live: LiveCall? = null
    private var focus: AudioFocusRequest? = null

    private val modeListener = AudioManager.OnModeChangedListener { send(Command.ModeChanged(it)) }
    private val deviceListener =
        AudioManager.OnCommunicationDeviceChangedListener { send(Command.RoutesChanged) }

    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (active: Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
        onEnded: () -> Unit,
    ) = send(Command.Start(gen, onActive, onRoutes, onEnded))

    override fun end(gen: Int, reason: VoiceCall.Reason) = send(Command.End(gen))

    override fun requestActive(gen: Int) = send(Command.RequestActive(gen))

    override fun requestRoute(gen: Int, routeId: String) = send(Command.RequestRoute(gen, routeId))

    private fun send(c: Command) {
        commands.trySend(c).onFailure { Log.e(TAG, "dropping $c: $it") }
    }

    // ---- consumer only, below ----

    private fun handleStart(c: Command.Start) {
        live?.let { handleEnd(it.gen) }
        val l = LiveCall(c.gen, c.onActive, c.onRoutes, c.onEnded)
        live = l

        audio.addOnModeChangedListener(executor, modeListener)
        audio.addOnCommunicationDeviceChangedListener(executor, deviceListener)
        acquire()
        publishRoutes(l)
    }

    /**
     * Take the mode and the focus. Split out because a resume after a cellular call has to redo
     * exactly this: the other owner's MODE_IN_CALL displaces ours, and focus does not come back on
     * its own.
     */
    private fun acquire() {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // Losing focus is logged and nothing else: tearing capture down on focus loss is the
            // mistake the earlier spike recorded, because a transient loss would kill the session.
            .setOnAudioFocusChangeListener { Log.i(TAG, "audio focus change: $it") }
            .build()
        focus = request
        val granted = audio.requestAudioFocus(request)
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        Log.i(TAG, "acquire: focus=$granted mode=${audio.mode}")
    }

    private fun release() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }

    private fun handleEnd(gen: Int) {
        val l = live ?: return
        if (l.gen != gen) return
        live = null
        audio.removeOnModeChangedListener(modeListener)
        audio.removeOnCommunicationDeviceChangedListener(deviceListener)
        release()
        l.onEnded()
    }

    private fun handleRequestActive(gen: Int) {
        val l = live ?: return
        if (l.gen != gen || !l.held) return
        acquire()
        l.held = false
        l.onActive(true)
    }

    private fun handleRequestRoute(gen: Int, routeId: String) {
        val l = live ?: return
        if (l.gen != gen) return
        val device = audio.availableCommunicationDevices.firstOrNull { it.id.toString() == routeId }
        if (device == null) {
            Log.i(TAG, "route $routeId is gone; dropping the request")
            return
        }
        // Fire and forget, as the seam says: the move comes back through the device listener if the
        // platform makes it.
        val ok = audio.setCommunicationDevice(device)
        Log.i(TAG, "setCommunicationDevice ${device.productName}/${device.id} ok=$ok")
    }

    /**
     * MODE_IN_CALL or MODE_RINGTONE means the cell radio owns the audio — a call arriving is a
     * hold, and the mode leaving that pair is the resume core-telecom never reports.
     *
     * The wrinkle this spike exists to measure: another VoIP app also runs MODE_IN_COMMUNICATION,
     * and this cannot tell its mode from ours. That case reads as "not held" here, which is the
     * concurrent-microphone behaviour the replacement accepts.
     */
    private fun handleModeChanged(mode: Int) {
        val l = live ?: return
        val takenOver = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE
        if (takenOver == l.held) return
        l.held = takenOver
        if (takenOver) {
            Log.i(TAG, "held: platform mode is $mode")
            l.onActive(false)
        } else {
            Log.i(TAG, "resuming: platform mode is $mode")
            acquire()
            l.onActive(true)
        }
    }

    private fun publishRoutes(l: LiveCall) {
        l.onRoutes(
            AudioRoutes(
                audio.availableCommunicationDevices.map { it.toAudioRoute() }
                    .distinctBy { it.id }
                    .sorted(),
                audio.communicationDevice?.toAudioRoute(),
            ),
        )
    }

    // Last, after every field the consumer reads — a consumer launched above a declaration can run
    // against an uninitialized field. Same ordering rule as TelecomCall's.
    init {
        scope.launch {
            for (c in commands) {
                // A throw would kill the consumer silently and permanently, and every later command
                // would queue forever behind it.
                runCatching {
                    when (c) {
                        is Command.Start -> handleStart(c)
                        is Command.End -> handleEnd(c.gen)
                        is Command.RequestActive -> handleRequestActive(c.gen)
                        is Command.RequestRoute -> handleRequestRoute(c.gen, c.routeId)
                        is Command.ModeChanged -> handleModeChanged(c.mode)
                        is Command.RoutesChanged -> live?.let { publishRoutes(it) }
                    }
                }.onFailure { Log.e(TAG, "command $c failed", it) }
            }
        }
    }

    private companion object {
        const val TAG = "AudioManagerCall"
    }
}
