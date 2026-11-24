package me.danielstiner.dumble.ui.latency

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import me.danielstiner.dumble.ui.theme.DumbleTheme

/** Runs one latency check on the extras it was started with and shows the result. Only adb can
 *  start it: see the manifest. Back closes the page and cancels a check still running. */
@AndroidEntryPoint
class LatencyCheckActivity : ComponentActivity() {
    private val check: LatencyCheckViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A rotation or a restore after process death comes with a saved state and the first
        // launch's extras: neither starts a second check.
        if (savedInstanceState == null) {
            check.start(
                intent.getStringExtra("host"),
                intent.getIntExtra("port", -1).takeIf { it != -1 },
                intent.getStringExtra("fingerprint"),
            )
        }
        setContent {
            DumbleTheme {
                val state by check.state.collectAsStateWithLifecycle()
                LatencyCheckScreen(state, Modifier.fillMaxSize())
            }
        }
    }
}
