package com.example.drumble.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == "ACTION_HANGUP") {
            CallManager.disconnect()
        }
    }
}
