package me.danielstiner.dumble.session

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.danielstiner.dumble.mumble.voice.TransmitMode
import me.danielstiner.dumble.mumble.voice.VoiceSender
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * One session's capture: what the lifecycle needs from the session, and the flags it reads to
 * decide whether this session's microphone should be open.
 */
internal class CaptureState(
    /** For logs. */
    val id: Int,
    /** Speaking, capture stats and the call-held banner are published through it. */
    val publisher: Publisher,
    /** Whether the session is still live; takes the publisher's lock. */
    val isLive: () -> Boolean,
    /** The user's own mute or a deafen, which keeps the gate shut. */
    val muted: () -> Boolean,
    val sendVoice: (ByteArray) -> Boolean,
    /** Builds this client's native engine; null when the device refuses. */
    val newCapture: () -> VoiceSender.CaptureHandle?,
    /** This client's transmit mode. */
    val transmitMode: () -> TransmitMode,
) {
    /** The open microphone, or null. One field, one lifetime: splitting handle and pump
     *  would leave a window where a hold could open a second microphone stream. Written only
     *  by the lifecycle consumer (the one coroutine that runs [CaptureLifecycle]'s commands);
     *  read on the UI thread by [CaptureLifecycle.apply]. */
    @Volatile var sender: VoiceSender? = null
    /** Talk is held. The current state, not the press event, so a sender rebuilt under a
     *  still-held button comes up transmitting. UI thread writes it;
     *  [CaptureLifecycle.openCapture] reads it after publishing [sender] — see
     *  [CaptureLifecycle.apply] for why that order matters, and keep both `@Volatile`. */
    @Volatile var pressed: Boolean = false
    /** The app wants this session's microphone open; [CaptureLifecycle.reconcile] opens it from
     *  this flag. Set by Acquire, cleared by Release and by a pump exit that was not requested.
     *  Lifecycle consumer only. */
    var wanted: Boolean = false
    /** A release is in flight: stop() has returned and only the pump's own exit will free the
     *  engine. Keeps [CaptureLifecycle.reconcile] from starting a second release meanwhile;
     *  cleared by [CaptureLifecycle.onPumpExited]. Lifecycle consumer only. */
    var releasing: Boolean = false
    /** The platform is holding this session's call. Lifecycle consumer only. */
    var held: Boolean = false
}

/**
 * Opens and closes the microphone for every session. Each request is a command on one channel,
 * and one coroutine, the "lifecycle consumer", runs them in order. A request sets a flag on the
 * session's [CaptureState] (a "level": what is true now, not what happened); [reconcile] then
 * opens or closes the microphone to match the flags.
 *
 * One per [SessionParts], so an ended session's release runs before the next session's open. See
 * docs/capture.md, "Lifecycle".
 */
internal class CaptureLifecycle(
    private val startPump: (VoiceSender) -> Unit,
    private val captureClock: TimeSource,
    private val stuckPumpMillis: Long,
    context: CoroutineContext,
    blocking: CoroutineDispatcher,
) {
    /** Long-lived; the wedge watchdog's delays run here. */
    private val scope = CoroutineScope(context + SupervisorJob())
    /** Never cancelled; the consumer runs here. */
    private val blockingScope = CoroutineScope(SupervisorJob() + blocking)

    private sealed interface CaptureCommand {
        /** Capture is wanted on this session — the microphone became ready, or Talk was
         *  pressed. Pairs with [Release]. Sets a flag rather than opening anything, so repeats
         *  are free. */
        data class Acquire(val client: CaptureState) : CaptureCommand
        /** The platform has taken, or returned, the input device for [client]'s call. */
        data class Held(val client: CaptureState, val held: Boolean) : CaptureCommand
        data class Release(val client: CaptureState) : CaptureCommand
        data class PumpExited(val client: CaptureState, val sender: VoiceSender) : CaptureCommand
        data class WedgeCheck(val client: CaptureState, val sender: VoiceSender) : CaptureCommand
    }

    /** The client that opened last; cleared by its release. Lifecycle consumer only. */
    private var holder: CaptureState? = null

    private val captureCommands = Channel<CaptureCommand>(Channel.UNLIMITED)

    init {
        // The consumer: the one coroutine that runs every capture command. On blockingScope, never
        // a session's or a connection's scope: teardown cancels those synchronously and would
        // discard queued commands, leaking the engine and the microphone. Every handler also
        // blocks: newCapture() on the HAL, stop() on OboeCapture::close(). runCatching because a
        // SupervisorJob does not restart a coroutine that threw, and a dead consumer fails
        // silently and permanently.
        //
        // Below the `captureCommands` declaration: Kotlin runs property initializers and init
        // blocks in textual order, so a consumer launched above it can start against a still-null
        // channel (a rare "Channel.iterator() on null" crash).
        blockingScope.launch {
            for (cmd in captureCommands) {
                runCatching { dispatch(cmd) }
                    .onFailure { Log.e(TAG, "capture command failed: $cmd", it) }
            }
        }
    }

    fun acquire(client: CaptureState) = send(CaptureCommand.Acquire(client))

    fun held(client: CaptureState, held: Boolean) = send(CaptureCommand.Held(client, held))

    /** Caller holds the client's lock, in the critical section that ended its session. */
    fun release(client: CaptureState) = send(CaptureCommand.Release(client))

    /**
     * Any thread; never blocks. Cannot fail: the channel is UNLIMITED and never closed, and its
     * consumer lives on [blockingScope], which is never cancelled. Checked because the failure
     * would be silent and permanent — a dropped Release strands a microphone.
     */
    private fun send(cmd: CaptureCommand) {
        if (captureCommands.trySend(cmd).isFailure) Log.e(TAG, "capture command dropped: $cmd")
    }

    /** Lifecycle consumer only — every capture transition is serialised through here. */
    private fun dispatch(cmd: CaptureCommand) {
        when (cmd) {
            is CaptureCommand.Acquire -> onAcquire(cmd.client)
            is CaptureCommand.Held -> onHeld(cmd.client, cmd.held)
            is CaptureCommand.Release -> onRelease(cmd.client)
            is CaptureCommand.PumpExited -> onPumpExited(cmd.client, cmd.sender)
            is CaptureCommand.WedgeCheck -> onWedgeCheck(cmd.client, cmd.sender)
        }
    }

    /** Lifecycle consumer only. */
    private fun onAcquire(client: CaptureState) {
        client.wanted = true
        reconcile(client)
    }

    /**
     * The platform took the input device from [client]'s call (for another call, the phone's or
     * another app's) or gave it back. Closes the microphone on a hold and reopens it on a
     * resume. Lifecycle consumer only.
     */
    private fun onHeld(client: CaptureState, held: Boolean) {
        // Published under the check's lock: a connect() between the two would clear the banner
        // only for this stale hold to set it again on the new session.
        val live = synchronized(client.publisher.lock) {
            client.isLive().also { if (it) client.publisher.state.callHeld.value = held }
        }
        // An ended session's hold changes nothing: its capture is released, and the banner
        // belongs to the live one.
        if (live) {
            Log.i(TAG, "call ${if (held) "held" else "resumed"} id=${client.id}")
            client.held = held
            reconcile(client)
        } else {
            Log.w(TAG, "call ${if (held) "hold" else "resume"} dropped: id=${client.id} not live")
        }
    }

    /**
     * [client]'s session has ended: close its microphone, if open. The command was
     * queued under [Publisher.lock] in the critical section that ended the session, which puts it
     * ahead of anything the next session can queue. Lifecycle consumer only.
     */
    private fun onRelease(client: CaptureState) {
        client.wanted = false
        if (holder === client) holder = null
        reconcile(client)
    }

    /** Open or close [client]'s microphone to match its flags. The only place a [VoiceSender] is
     *  built or released. Lifecycle consumer only. */
    private fun reconcile(client: CaptureState) {
        if (client.wanted && !client.held && client.isLive()) {
            // Already open, or still being released. onPumpExited reconciles again once a release
            // finishes; opening here would put two microphones open at once.
            if (client.sender != null) return
            openCapture(client)
        } else {
            val sender = client.sender ?: return
            if (!client.releasing) beginRelease(client, sender)
        }
    }

    /** Build the native engine and its sender for [client] and publish the sender.
     *  Lifecycle consumer only; blocks in newCapture() — a full HAL open — which is why the
     *  consumer runs on blockingScope. */
    private fun openCapture(client: CaptureState) {
        holder?.takeIf { it !== client }?.let { other ->
            // One microphone across clients: the last to open wins, and the loser must not take
            // it back when its pump exits.
            Log.i(TAG, "capture id=${client.id} preempts id=${other.id}")
            other.wanted = false
            other.sender?.let { if (!other.releasing) beginRelease(other, it) }
        }
        // Leaves `wanted` set on failure, unlike onPumpExited: an open happens once per command,
        // so a failing one cannot loop, and a Talk press asks again anyway.
        val handle = client.newCapture() ?: return
        val sender = VoiceSender(
            handle, { client.sendVoice(it) },
            onExit = { s -> send(CaptureCommand.PumpExited(client, s)) },
            onSpeaking = { speaking -> client.publisher.publish { selfSpeaking.value = speaking } },
            onStats = { stats -> client.publisher.publish { captureStats.value = stats } },
            clock = captureClock,
        )
        // Recheck: the session can end on another thread while newCapture() blocks.
        if (!client.isLive()) { handle.stop(); handle.destroy(); return }
        // Published before the levels are read — the mirror of apply()'s order; see its KDoc.
        client.sender = sender
        holder = client
        // Before the pump starts, so its first poll already reads the mode and the gate.
        sender.apply(client.transmitMode, { wantsGate(client) })
        startPump(sender)
    }

    /** Start releasing [sender]: close its stream synchronously, then leave the engine for the
     *  pump's own exit to free via [onPumpExited]. Lifecycle consumer only; callers guarantee
     *  `!client.releasing`. */
    private fun beginRelease(client: CaptureState, sender: VoiceSender) {
        // Synchronous, inline: stop() returns once the pump has closed the Oboe stream (or, wedged,
        // after its bound). That ordering is the entire one-microphone invariant — making it
        // asynchronous would silently reopen the hole.
        sender.stop()
        // The speaking indicator belongs to the session being released. A pump that exited already
        // lowered it; a wedged one has not, and left up it would carry into whatever opens next.
        client.publisher.state.selfSpeaking.value = false
        // Set only once stop() has returned. Set before it, a throw out of that JNI call would
        // leave `sender` non-null and `releasing` true for good: reconcile would neither reopen
        // nor retry, and the engine would never be destroyed. Left false, the next reconcile tries
        // the release again.
        client.releasing = true
        // Armed after stop() returns, not before: stop() can itself wait out its bound, so timing
        // across it would report a slow HAL as a wedged pump.
        scope.launch { delay(stuckPumpMillis); send(CaptureCommand.WedgeCheck(client, sender)) }
    }

    /**
     * The pump of [sender] has exited — the only signal that no thread can still touch its
     * engine, and therefore the only place the engine is freed. Ends by reconciling again, because
     * a resume or a fresh Acquire may have arrived while the release was in flight and was
     * refused then. Lifecycle consumer only.
     */
    private fun onPumpExited(client: CaptureState, sender: VoiceSender) {
        // Identity, not client: a later open may already have published a different sender, and
        // freeing that one would delete a live engine.
        if (client.sender === sender) {
            sender.destroy()
            client.sender = null
            client.publisher.publish { captureStats.value = null }
        } else {
            // Unreachable — reconcile refuses to open while `sender` is set, so at most one exit
            // can be outstanding. Logged rather than assumed, so a wrong argument is visible.
            Log.e(TAG, "pump exit for a capture that is no longer this session's")
        }
        client.releasing = false
        // Anything but a requested exit is terminal for this engine. Rebuilding on the spot would
        // be a loop of full HAL opens against a cause that has not changed, so the retry stays
        // user-driven.
        if (sender.stopReason != VoiceSender.StopReason.REQUESTED) client.wanted = false
        reconcile(client)
    }

    /**
     * Watchdog armed by [beginRelease]: if the pump has still not exited this long after its stop,
     * it is wedged in native code, and this log line is the only diagnostic that will ever say so.
     * The leak it reports is deliberate — freeing an engine under a live pump is the
     * use-after-free this design exists to prevent. Lifecycle consumer only.
     */
    private fun onWedgeCheck(client: CaptureState, sender: VoiceSender) {
        if (client.sender !== sender) return   // released in time, or already replaced
        Log.e(
            TAG,
            "capture pump has not exited after ${stuckPumpMillis}ms " +
                "(stopReason=${sender.stopReason}); engine and input stream are leaked unless it " +
                "exits later",
        )
    }

    /** Talk pressed or released: the level first, then the gate, as [apply] requires. */
    fun press(client: CaptureState, on: Boolean) {
        client.pressed = on
        apply(client)
    }

    /**
     * Re-derive the gate: open when not muted and either Talk is held or voice activity is on.
     * Wanting it open also asks for capture, which is what brings one back after a terminal
     * engine failure.
     *
     * The caller writes its level (`pressed`, mute or the mode) first, then this reads the live
     * sender — the mirror of [openCapture], which publishes the sender and then reads the
     * levels. Each side writes one volatile and reads the other, so a press cannot be lost to a
     * racing open: for both to miss, this read of `sender` would have to precede openCapture's
     * write of it *and* openCapture's read of the level precede the caller's write of it, which
     * program order forbids. Every level must stay `@Volatile` for that. When both act, the gate
     * is set twice, which costs nothing.
     */
    fun apply(client: CaptureState) {
        if (wantsGate(client)) send(CaptureCommand.Acquire(client))
        client.sender?.apply(client.transmitMode, { wantsGate(client) })
    }

    private fun wantsGate(client: CaptureState) =
        !client.muted() && (client.pressed || client.transmitMode() == TransmitMode.VoiceActivity)

    companion object {
        /** How long a stopped pump may take to exit before the watchdog calls it wedged. */
        const val STUCK_PUMP_MILLIS = 1_000L
        private const val TAG = "MumbleConn"
    }
}
