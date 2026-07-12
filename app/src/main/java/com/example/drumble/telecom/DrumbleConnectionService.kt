package com.example.drumble.telecom

import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log

class DrumbleConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "DrumbleConnectionService"
    }

    override fun onCreate() {
        super.onCreate()
        CallManager.init(this)
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection")
        val connection = DrumbleConnection().apply {
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
            audioModeIsVoip = true
            setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
            setInitializing()
            setActive() 
        }
        CallManager.setConnection(connection)
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed")
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateIncomingConnection")
        val connection = DrumbleConnection().apply {
            connectionProperties = Connection.PROPERTY_SELF_MANAGED
            audioModeIsVoip = true
            setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
            setInitializing()
        }
        CallManager.setConnection(connection)
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed")
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
    }
}
