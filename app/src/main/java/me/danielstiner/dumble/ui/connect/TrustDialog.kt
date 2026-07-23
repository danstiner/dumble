package me.danielstiner.dumble.ui.connect

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import me.danielstiner.dumble.mumble.connection.ConnectionStatus

/** Shown when status is AwaitingTrust (first contact) or PinMismatch (cert changed). */
@Composable
fun TrustDialog(status: ConnectionStatus, onTrust: () -> Unit, onCancel: () -> Unit) {
    val (title, body, confirm) = when (status) {
        is ConnectionStatus.AwaitingTrust ->
            Triple("Unrecognized server certificate", grouped(status.fingerprint), "Trust & connect")
        is ConnectionStatus.PinMismatch ->
            Triple(
                "Certificate changed",
                "This server's certificate is different from the one you trusted. It may be a routine key " +
                    "rotation — or someone intercepting the connection.\n\nStored:\n${grouped(status.stored)}\n\n" +
                    "Now:\n${grouped(status.presented)}",
                "Replace & connect",
            )
        else -> return
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onTrust) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** SHA-256 hex grouped into byte pairs for eyeball comparison. */
private fun grouped(fp: String): String = fp.chunked(2).joinToString(":")
