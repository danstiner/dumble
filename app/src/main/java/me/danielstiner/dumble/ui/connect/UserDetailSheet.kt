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
import me.danielstiner.dumble.mumble.protocol.UserStats
import me.danielstiner.dumble.mumble.voice.PlayoutDelay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlinx.coroutines.delay

/**
 * One speaker's receive-path measurements.
 *
 * [delay] is what their audio costs to reach us, and only that — their capture delay is not on
 * the wire, so the total is shown as a floor. Its parts are indented under it and add up to it.
 *
 * [stats] is what the server measures about them. Both pings get a row even though only one leg
 * carries voice: murmur exchanges UDP pings only with a peer that has a working UDP path, so a
 * UDP reading is the evidence for which — a dash for everyone until dumble gains a UDP path of
 * its own. Our own round trip is in the status line above the roster.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserDetailSheet(
    session: Int,
    name: String,
    delay: PlayoutDelay?,
    stats: UserStats?,
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
            StatRow("Latency", delay?.total.floor())
            StatRow("Network", delay?.network.label(), part = true)
            StatRow("Jitter buffer", delay?.jitterBuffer.label(), part = true)
            StatRow("Audio output", delay?.audioOutput.label(), part = true)
            StatRow("TCP ping", stats?.tcpPing.label())
            StatRow("UDP ping", stats?.udpPing.label())
            StatRow("Jitter", stats?.jitter.label())
            StatRow("Bandwidth", stats?.bandwidthBitsPerSecond.kilobits())
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
 * Whole milliseconds, except below 10 where the tenth is what tells a LAN peer's fraction of a
 * millisecond from a broken zero. An exact zero stays whole: a drained queue really is 0.
 */
private fun Duration?.label(): String {
    val ms = this?.toDouble(DurationUnit.MILLISECONDS) ?: return NoReading
    return (if (ms > 0 && ms < 10) "%.1f ms" else "%.0f ms").format(ms)
}

/** A floor, since their capture is never in it. */
private fun Duration?.floor(): String = this?.let { "> ${it.label()}" } ?: NoReading

/** Kilobits, because a voice stream is tens of them and the bits are noise at this width. */
private fun Int?.kilobits(): String =
    this?.let { "%.1f kbit/s".format(it / 1000f) } ?: NoReading

/** [part] marks a row as a component of the one above it: indented, and a step down in size. */
@Composable
private fun StatRow(label: String, value: String, part: Boolean = false) {
    val style = if (part) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    Row(
        Modifier.fillMaxWidth()
            .padding(start = if (part) 16.dp else 0.dp)
            .padding(vertical = if (part) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = style,
            color = if (part) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
        )
    }
}
