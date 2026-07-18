package me.danielstiner.dumble

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import me.danielstiner.dumble.mumble.MumbleManager
import me.danielstiner.dumble.mumble.MumbleServerConfig
import me.danielstiner.dumble.telecom.CallManager
import me.danielstiner.dumble.ui.DumbleApp
import me.danielstiner.dumble.ui.theme.DumbleTheme

class ActiveCallActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled lazily on the next Connect tap */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallManager.init(this)
        MumbleManager.init(this)
        requestCallPermissions()

        setContent {
            DumbleTheme {
                DumbleApp(
                    onConnect = { config -> onConnect(config) },
                    onHangUp = { CallManager.hangUp() },
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
        CallManager.startCall()
        MumbleManager.connect(config)
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
