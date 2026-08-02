package me.danielstiner.dumble.ui.connect

import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.chat.ChatMessage
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    mySession: Int,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val listState = rememberLazyListState()
    // Always snap to newest on arrival; v1 skips "only if already at bottom" tracking.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    Scaffold(
        // imePadding on the Scaffold lifts the bottom bar with the keyboard while the top bar stays
        // put — a Column with the field at the bottom pushes the whole screen instead.
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Chat") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = draft, onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f), maxLines = 4,
                    placeholder = { Text("Message") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) onSend() }),
                )
                IconButton(onClick = onSend, enabled = draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // No item key: connection.messages is append-only (or cleared wholesale on reconnect),
            // never reordered, so positional identity is stable. ChatMessage has no id to key on.
            items(messages) { msg -> MessageRow(msg, mySession) }
        }
    }
}

// DateTimeFormatter is immutable/thread-safe, unlike SimpleDateFormat.
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@Composable
private fun MessageRow(msg: ChatMessage, mySession: Int) {
    Column(Modifier.fillMaxWidth()) {
        when (msg) {
            is ChatMessage.Remote -> {
                val actor = msg.actorSession
                val sender = when {
                    actor == null -> "System"
                    actor == mySession -> "You"
                    else -> msg.senderName ?: "user $actor"
                }
                // HTML→plaintext once per distinct body, not on every recomposition
                val body = remember(msg.htmlBody) {
                    Html.fromHtml(msg.htmlBody, Html.FROM_HTML_MODE_LEGACY).toString().trim()
                }
                Text("$sender · ${timeFormat.format(msg.receiveTime)}")
                Text(body)
            }
            is ChatMessage.Denied -> {
                Text("System · ${timeFormat.format(msg.receiveTime)}")
                Text(denyReasonText(msg.reason))
            }
        }
    }
}
