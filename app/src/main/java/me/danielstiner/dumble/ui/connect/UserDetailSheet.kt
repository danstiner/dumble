package me.danielstiner.dumble.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * One speaker's receive-path measurements.
 *
 * [playoutTargetMillis] is labelled for the buffer, not for delay: it is only the buffer's share
 * of the total, which runs 120-180 ms on a Pixel 7a where this reads 30.
 *
 * The ping rows are the other half of the path: the server's round trip to them, which it measures
 * and we cannot. Ours sits in the status line above the roster, so they read together.
 *
 * Voice path is derived rather than reported — murmur exchanges UDP pings only with a peer that
 * has a working UDP path, so an average there is the evidence. Cumulative since they connected, so
 * it says "has had UDP", not "is on UDP this second". Everyone reads TCP until dumble gains a UDP
 * path of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailSheet(
    session: Int,
    name: String,
    playoutTargetMillis: Int?,
    tcpPingMillis: Float?,
    udpPingMillis: Float?,
    onRefresh: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed on the subject, so switching users restarts the asking. The loop dies with the
    // composition, which is exactly how long anybody is looking at the answer.
    LaunchedEffect(session) {
        while (true) {
            onRefresh(session)
            delay(RefreshInterval)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarCircle(name, Modifier.size(AvatarSize))
                Text(
                    name,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            StatRow("Voice path", if (udpPingMillis != null) "UDP" else "TCP")
            StatRow("TCP ping", tcpPingMillis.millis())
            StatRow("UDP ping", udpPingMillis.millis())
            StatRow("Jitter buffer", playoutTargetMillis?.let { "$it ms" } ?: NoReading)
        }
    }
}

// One token for every absent reading. Several are absent by design rather than by timing — no UDP
// leg for a tunnelling peer, no jitter buffer for ourselves, since our own audio is never decoded
// locally — and telling those apart per row cost more than it explained.
private const val NoReading = "—"

// Fast enough that the number tracks a link going bad while someone watches it, slow enough to
// stay a rounding error against the ping the server is already running on its own.
private val RefreshInterval = 2.seconds

/**
 * Whole milliseconds, except below 10 where a tenth is what distinguishes readings: a LAN peer
 * measures a fraction of a millisecond, and rounding that to "0 ms" reads as a broken zero rather
 * than as a fast link.
 */
private fun Float?.millis(): String = when {
    this == null -> NoReading
    this < 10f -> "%.1f ms".format(Locale.ROOT, this)
    else -> "${roundToInt()} ms"
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
