package me.danielstiner.dumble.mumble.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
 * All state lives on the main looper, so commands apply in send order.
 */
class AndroidVoiceCall(private val context: Context) : VoiceCall {

    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
    private val main = Handler(Looper.getMainLooper())

    private class Live(val gen: Int, val onRoutes: (AudioRoutes) -> Unit)

    // Main only, like everything below.
    private var live: Live? = null
    // Arrivals are found by diff: nothing reports the communication-device list changing, and a
    // headset's call-link device is never announced as added (measured).
    private var known: Set<String> = emptySet()

    private val routeListener = AudioManager.OnCommunicationDeviceChangedListener { live?.let(::reconcile) }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<AudioDeviceInfo>) { live?.let(::reconcile) }
        override fun onAudioDevicesRemoved(removed: Array<AudioDeviceInfo>) { live?.let(::reconcile) }
    }

    override fun start(
        gen: Int,
        endpoint: MumbleEndpoint,
        username: String,
        onActive: (active: Boolean) -> Unit,
        onRoutes: (AudioRoutes) -> Unit,
        onEnded: () -> Unit,
    ) {
        main.post { handleStart(gen, endpoint.host, onRoutes) }
    }

    override fun end(gen: Int, reason: VoiceCall.Reason) {
        main.post { handleEnd(gen) }
    }

    /** Nothing holds this call, so there is nothing to resume. */
    override fun requestActive(gen: Int) = Unit

    override fun requestRoute(gen: Int, routeId: String) {
        main.post { if (live?.gen == gen) route(routeId) }
    }

    /**
     * A start while live supersedes: mode, route and listeners carry over, so a headset's call
     * link is not closed only to be reopened.
     */
    private fun handleStart(gen: Int, host: String, onRoutes: (AudioRoutes) -> Unit) {
        val superseding = live != null
        val l = Live(gen, onRoutes)
        live = l
        // Again on a supersede, only to show the new host.
        VoiceService.start(context, host)
        if (!superseding) {
            // Route before mode: the other way round spends ~1 s on the earpiece (measured).
            preferredRoute(routes())?.let { route(it.id) }
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            // What is here at start is not an arrival.
            known = routes().mapTo(HashSet()) { it.id }
            audio.addOnCommunicationDeviceChangedListener(context.mainExecutor, routeListener)
            audio.registerAudioDeviceCallback(deviceCallback, main)
        }
        reconcile(l)
    }

    private fun handleEnd(gen: Int) {
        if (live?.gen != gen) return
        live = null
        audio.removeOnCommunicationDeviceChangedListener(routeListener)
        audio.unregisterAudioDeviceCallback(deviceCallback)
        VoiceService.stop(context)
        audio.clearCommunicationDevice()
        audio.mode = AudioManager.MODE_NORMAL
    }

    /** A headset's arrival, then the routes shown. */
    private fun reconcile(l: Live) {
        val routes = routes()
        arrivedHeadset(known, routes)?.let { route(it.id) }
        known = routes.mapTo(HashSet()) { it.id }
        l.onRoutes(AudioRoutes(routes.distinctBy { it.id }.sorted(), audio.communicationDevice?.toAudioRoute()))
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
