package com.example.drumble.telecom

import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import com.example.drumble.mumble.MumbleManager

class DrumbleConnection : Connection() {

    companion object {
        private const val TAG = "DrumbleConnection"
    }

    override fun onShowIncomingCallUi() {
        Log.d(TAG, "onShowIncomingCallUi")
        // TODO: Show incoming call notification/UI
    }

    override fun onAnswer() {
        Log.d(TAG, "onAnswer")
        setActive()
        // TODO: Start audio processing
    }

    override fun onReject() {
        Log.d(TAG, "onReject")
        MumbleManager.disconnect()
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        CallManager.setConnection(null)
    }

    override fun onCallAudioStateChanged(state: android.telecom.CallAudioState?) {
        Log.d(TAG, "onCallAudioStateChanged: $state")
        // TODO: Update UI or audio routing based on state (e.g. earpiece vs speaker)
    }

    override fun onDisconnect() {
        Log.d(TAG, "onDisconnect")
        MumbleManager.disconnect()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        CallManager.setConnection(null)
        // TODO: Stop audio processing
    }

    override fun onAbort() {
        Log.d(TAG, "onAbort")
        MumbleManager.disconnect()
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
        CallManager.setConnection(null)
    }

    override fun onHold() {
        Log.d(TAG, "onHold")
        setOnHold()
    }

    override fun onUnhold() {
        Log.d(TAG, "onUnhold")
        setActive()
    }
}
