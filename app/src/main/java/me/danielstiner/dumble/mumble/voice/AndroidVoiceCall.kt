package me.danielstiner.dumble.mumble.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.util.Log
import me.danielstiner.dumble.mumble.net.MumbleEndpoint
import me.danielstiner.dumble.service.VoiceService

/**
 * Takes MODE_IN_COMMUNICATION and a communication device itself, with no Telecom call and no
 * audio focus. Without a Telecom call no dialer draws over our screen, and a Bluetooth headset
 * still gets its call link (SCO, the two-way link that carries its microphone), which a Telecom
 * call hidden from dialers does not. Without focus, other apps' media keeps playing.
 *
 * Two things hold the session: the phone taking the audio, and another app capturing voice. The
 * mode and the recording callback report both ends, so a hold resumes by itself.
 *
 * All state lives on the main looper, so commands apply in send order.
 */
class AndroidVoiceCall(
    private val context: Context,
    /** Ours among the recordings, where nothing else identifies one. */
    private val captureSession: Int = CaptureSessionId.get(context),
) : VoiceCall {

    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
    private val main = Handler(Looper.getMainLooper())

    private class Live(
        val gen: Int,
        val onActive: (Boolean) -> Unit,
        val onRoutes: (AudioRoutes) -> Unit,
    ) {
        /**
         * Per generation, never carried across a supersede: MumbleConnection opens capture for any
         * generation it was not told is held, so a new one starts not held and is told if it is.
         */
        var held = false
    }

    // Main only, like everything below.
    private var live: Live? = null
    /**
     * Whether this call has picked its route. Deferred while it starts held, so connecting during
     * another call takes neither that call's route nor its mode.
     */
    private var routed = false
    /**
     * Whether the mode has been set since this call started or last resumed — the only times it is.
     * A resume sets it whatever it reads: another app's call can end leaving IN_COMMUNICATION set
     * and still its own. In between, AudioService drops an owner with no voice playback or capture
     * after a 6 s grace and hands the mode back once one starts (measured: dropped 6.0 s into a
     * stalled connect), so setting it again there would only fight that.
     */
    private var modeTaken = false
    // Arrivals are found by diff: nothing reports the communication-device list changing, and a
    // headset's call-link device is never announced as added (measured).
    private var known: Set<String> = emptySet()

    private val modeListener = AudioManager.OnModeChangedListener { live?.let(::reconcile) }
    private val routeListener = AudioManager.OnCommunicationDeviceChangedListener { live?.let(::reconcile) }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) { live?.let(::reconcile) }
        override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) { live?.let(::reconcile) }
    }
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            live?.let(::reconcile)
        }
    }

    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (active: Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
        onEnded: () -> Unit,
    ) {
        main.post { handleStart(gen, endpoint.host, onActive, onRoutes) }
    }

    override fun end(gen: Int, reason: VoiceCall.Reason) {
        main.post { handleEnd(gen) }
    }

    /** A re-check, behind a Talk press or the held banner: the platform reports a hold ending itself. */
    override fun requestActive(gen: Int) {
        main.post { live?.takeIf { it.gen == gen }?.let(::reconcile) }
    }

    override fun requestRoute(gen: Int, routeId: String) {
        main.post {
            if (live?.gen == gen) {
                // A pick is the call's route even during a hold, so take() must not overwrite it
                // with its own deferred preferredRoute pick on resume.
                routed = true
                route(routeId)
            }
        }
    }

    /**
     * A start while live supersedes: listeners and whatever mode and route were taken carry over,
     * so a headset's call link is not closed only to be reopened.
     */
    private fun handleStart(
        gen: Int,
        host: String,
        onActive: (Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
    ) {
        val superseding = live != null
        val l = Live(gen, onActive, onRoutes)
        live = l
        // Again on a supersede, only to show the new host.
        VoiceService.start(context, host)
        if (!superseding) {
            // What is here at start is not an arrival.
            known = routes().mapTo(HashSet()) { it.id }
            audio.addOnModeChangedListener(context.mainExecutor, modeListener)
            audio.addOnCommunicationDeviceChangedListener(context.mainExecutor, routeListener)
            audio.registerAudioDeviceCallback(deviceCallback, main)
            audio.registerAudioRecordingCallback(recordingCallback, main)
        }
        reconcile(l)
    }

    private fun handleEnd(gen: Int) {
        if (live?.gen != gen) return
        live = null
        routed = false
        modeTaken = false
        audio.removeOnModeChangedListener(modeListener)
        audio.removeOnCommunicationDeviceChangedListener(routeListener)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        audio.unregisterAudioRecordingCallback(recordingCallback)
        VoiceService.stop(context)
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }

    /**
     * Everything the platform can change, re-read: the hold first — a call not held takes whatever
     * route and mode it lacks — then a headset's arrival, then the routes shown.
     */
    private fun reconcile(l: Live) {
        val held = isHeld(audio.mode, otherVoiceCaptures(audio.activeRecordingConfigurations, captureSession))
        if (held) modeTaken = false else take()
        val routes = routes()
        // Routed even while held: the request applies only once we own the mode again — measured,
        // one made during a cellular call left that call's route alone.
        arrivedHeadset(known, routes)?.let { route(it.id) }
        known = routes.mapTo(HashSet()) { it.id }
        if (held != l.held) {
            l.held = held
            Log.i(TAG, "${if (held) "held" else "resumed"} gen=${l.gen} mode=${audio.mode}")
            l.onActive(!held)
        }
        l.onRoutes(AudioRoutes(routes.distinctBy { it.id }.sorted(), audio.communicationDevice?.toAudioRoute()))
    }

    /**
     * The route, once per call, then the mode, once per start or resume. Route before mode: the
     * other way round (Discord's order) spends ~1 s on the earpiece (measured). A cellular call can
     * leave the mode NORMAL (measured: resumed at mode 0); the resume's set covers it.
     */
    private fun take() {
        if (!routed) {
            routed = true
            preferredRoute(routes())?.let { route(it.id) }
        }
        if (!modeTaken) {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            modeTaken = true
        }
    }

    private fun routes() = audio.availableCommunicationDevices.map { it.toAudioRoute() }

    private fun route(id: String) {
        val device = audio.availableCommunicationDevices.firstOrNull { it.id.toString() == id }
        if (device == null) {
            // Gone since the menu was drawn; the next device event redraws it.
            Log.w(TAG, "route $id is gone")
            return
        }
        // A device that vanished since the lookup throws on API 31 and returns false after.
        val moved = try {
            audio.setCommunicationDevice(device)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "route ${device.productName}/$id threw", e)
            false
        }
        Log.i(TAG, "route ${device.productName}/$id moved=$moved")
    }

    private companion object {
        const val TAG = "AndroidVoiceCall"
    }
}
