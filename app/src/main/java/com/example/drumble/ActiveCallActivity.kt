package com.example.drumble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.drumble.telecom.CallManager
import com.example.drumble.telecom.DrumbleConnectionService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ActiveCallActivity : AppCompatActivity() {

    private lateinit var telecomManager: TelecomManager
    private lateinit var phoneAccountHandle: PhoneAccountHandle
    
    private lateinit var statusView: TextView
    private lateinit var callButton: Button
    private lateinit var hangupButton: Button

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[android.Manifest.permission.RECORD_AUDIO] ?: false
        val postNotificationsGranted = permissions[android.Manifest.permission.POST_NOTIFICATIONS] ?: false
        if (!recordAudioGranted || !postNotificationsGranted) {
            statusView.text = "Permissions required for calls"
        }
    }

    private fun checkAndRequestPermissions() {
        requestPermissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallManager.init(this)

        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        phoneAccountHandle = PhoneAccountHandle(
            ComponentName(this, DrumbleConnectionService::class.java),
            "DrumbleID"
        )

        registerPhoneAccount()
        checkAndRequestPermissions()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val titleView = TextView(this).apply {
            text = "Drumble Call"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        layout.addView(titleView)

        statusView = TextView(this).apply {
            text = "Ready"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 64)
        }
        layout.addView(statusView)

        callButton = Button(this).apply {
            text = "Start Call"
            setOnClickListener {
                placeTestCall()
            }
        }
        layout.addView(callButton)

        hangupButton = Button(this).apply {
            text = "Hang Up"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener {
                CallManager.disconnect()
            }
        }
        layout.addView(hangupButton)

        val debugButton = Button(this).apply {
            text = "Debug: Echo Test"
            setOnClickListener {
                startActivity(Intent(this@ActiveCallActivity, EchoTestActivity::class.java))
            }
        }
        layout.addView(debugButton)

        setContentView(layout)

        observeCallState()
    }

    override fun onResume() {
        super.onResume()
        CallManager.setUiVisible(true)
    }

    override fun onPause() {
        super.onPause()
        CallManager.setUiVisible(false)
    }

    private fun observeCallState() {
        lifecycleScope.launch {
            CallManager.activeConnection.collectLatest { connection ->
                if (connection != null) {
                    statusView.text = "In Call"
                    callButton.visibility = View.GONE
                    hangupButton.visibility = View.VISIBLE
                } else {
                    statusView.text = "Ready"
                    callButton.visibility = View.VISIBLE
                    hangupButton.visibility = View.GONE
                }
            }
        }
    }

    private fun registerPhoneAccount() {
        val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "Drumble")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        telecomManager.registerPhoneAccount(phoneAccount)
    }

    private fun placeTestCall() {
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
        }
        try {
            telecomManager.placeCall(Uri.fromParts("tel", "DrumbleUser", null), extras)
        } catch (e: SecurityException) {
            statusView.text = "Error: Permission denied"
        } catch (e: Exception) {
            statusView.text = "Error: ${e.message}"
        }
    }
}
