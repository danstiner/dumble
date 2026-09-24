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
 * The session's audio mode, route and microphone service, run as Discord runs a voice channel
 * (measured): our own MODE_IN_COMMUNICATION, a communication device AudioService opens SCO for, and
 * no Telecom call or audio focus. With no Telecom call no dialer draws its screen over ours, and
 * Bluetooth still gets SCO, which a Telecom call hidden from dialers does not. With no focus, other
 * apps' media keeps playing, at call quality over SCO.
 *
 * All state belongs to the main looper: calls post to it and the listeners run on it, which keeps
 * the seam's contract that commands apply in send order.
 */
class AndroidVoiceCall(private val context: Context) : VoiceCall {

    private val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
    private val main = Handler(Looper.getMainLooper())

    private class Live(val gen: Int, val onRoutes: (AudioRoutes) -> Unit)

    // Main only, like everything below.
    private var live: Live? = null
    /**
     * Communication-device ids last seen. No listener reports that list changing, and a headset's
     * SCO device is never itself announced as added (measured), so arrivals are found by diff.
     */
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
     * A start while a call is live supersedes it: listeners, service, mode and route carry over,
     * since releasing them between attempts would drop SCO only for the successor to reopen it.
     */
    private fun handleStart(gen: Int, host: String, onRoutes: (AudioRoutes) -> Unit) {
        val superseding = live != null
        val l = Live(gen, onRoutes)
        live = l
        // Again on a supersede, only to show the new host.
        VoiceService.start(context, host)
        if (!superseding) {
            // Route before mode: the other way round (Discord's order) spends ~1 s on the earpiece
            // (measured).
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
            // Gone between the menu rendering and the tap; the next device or route event
            // republishes the menu.
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
