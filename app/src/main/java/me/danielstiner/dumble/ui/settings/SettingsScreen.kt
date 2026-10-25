package me.danielstiner.dumble.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.danielstiner.dumble.mumble.voice.TransmitMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAbout: () -> Unit,
    transmitMode: TransmitMode,
    onSelectTransmitMode: (TransmitMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // A segmented row, not radio rows: one control showing both modes reads faster.
            Text(
                "Microphone",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                TransmitMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = mode == transmitMode,
                        onClick = { onSelectTransmitMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, TransmitMode.entries.size),
                    ) { Text(transmitModeLabel(mode)) }
                }
            }
            // Only the selected mode's line: the row already names the alternatives.
            Text(
                transmitModeDescription(transmitMode),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            )
            HorizontalDivider()
            // Below 37 the permission does not exist and local servers need nothing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
                LocalNetworkSwitch()
                HorizontalDivider()
            }
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onAbout),
                supportingContent = { Text("Version and open source licenses") },
            ) { Text("About") }
        }
    }
}

/**
 * The permission itself, not a stored preference: the grant already is the user's choice. Connect
 * asks on its own for a host that reads as local; this is for a local server reached by name, which
 * only a DNS lookup in the middle of connect could detect.
 */
@RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
@Composable
private fun LocalNetworkSwitch() {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
    fun granted() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(granted()) }
    // Denied with no rationale to show: a request would come back denied with no dialog, so only
    // the system page can grant it. Also true after a first dialog dismissed unanswered, where the
    // page grants it just as well.
    var blocked by rememberSaveable { mutableStateOf(false) }
    // The system settings page can change it while we are away.
    LifecycleResumeEffect(Unit) {
        allowed = granted()
        onPauseOrDispose {}
    }
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        )
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        blocked = !it && activity?.shouldShowRequestPermissionRationale(permission) == false
    }
    ListItem(
        // Revoking our own grant waits for the process to die, so turning it off goes to the system
        // page too.
        modifier = Modifier.fillMaxWidth().toggleable(allowed, role = Role.Switch) { on ->
            if (on && !blocked) request.launch(permission) else openAppSettings()
        },
        supportingContent = {
            Text(
                if (blocked && !allowed) "Allow it in system settings"
                else "Needed to reach a server on your local network"
            )
        },
        trailingContent = { Switch(checked = allowed, onCheckedChange = null) },
    ) { Text("Local network servers") }
}

private fun transmitModeLabel(mode: TransmitMode) = when (mode) {
    TransmitMode.PushToTalk -> "Push to talk"
    TransmitMode.VoiceActivity -> "Voice activity"
}

private fun transmitModeDescription(mode: TransmitMode) = when (mode) {
    TransmitMode.PushToTalk -> "Hold the Talk button to transmit"
    TransmitMode.VoiceActivity -> "Transmits whenever you speak"
}
