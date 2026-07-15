package me.danielstiner.dumble

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import me.danielstiner.dumble.mumble.MumbleManager
import me.danielstiner.dumble.mumble.MumbleServerConfig
import me.danielstiner.dumble.telecom.CallManager
import me.danielstiner.dumble.telecom.DumbleConnectionService
import me.danielstiner.dumble.ui.DumbleApp
import me.danielstiner.dumble.ui.theme.DumbleTheme

class ActiveCallActivity : ComponentActivity() {

    private lateinit var telecomManager: TelecomManager
    private lateinit var phoneAccountHandle: PhoneAccountHandle

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled lazily on the next Connect tap */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallManager.init(this)
        MumbleManager.init(this)

        telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        phoneAccountHandle = PhoneAccountHandle(
            ComponentName(this, DumbleConnectionService::class.java),
            "DumbleID",
        )
        registerPhoneAccount()
        requestCallPermissions()

        setContent {
            DumbleTheme {
                DumbleApp(
                    onConnect = { config -> onConnect(config) },
                    onHangUp = { CallManager.disconnect() },
                    onLaunchEchoTest = {
                        startActivity(Intent(this, EchoTestActivity::class.java))
                    },
                    onLaunchVadDebug = {
                        startActivity(Intent(this, VadDebugActivity::class.java))
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CallManager.setUiVisible(true)
    }

    override fun onPause() {
        super.onPause()
        CallManager.setUiVisible(false)
    }

    private fun onConnect(config: MumbleServerConfig) {
        if (!hasRecordAudio()) {
            Toast.makeText(
                this,
                "Microphone permission required — grant it, then tap Connect again",
                Toast.LENGTH_LONG,
            ).show()
            requestCallPermissions()
            return
        }
        placeTelecomCall()
        MumbleManager.connect(config)
    }

    private fun placeTelecomCall() {
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
        }
        try {
            telecomManager.placeCall(Uri.fromParts("tel", "DumbleUser", null), extras)
        } catch (_: SecurityException) {
            // Permission revoked between check and call; the next tap re-requests.
        }
    }

    private fun registerPhoneAccount() {
        val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "Dumble")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .build()
        telecomManager.registerPhoneAccount(phoneAccount)
    }

    private fun hasRecordAudio(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCallPermissions() {
        requestPermissionLauncher.launch(
            arrayOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        )
    }
}
