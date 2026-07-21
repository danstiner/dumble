# Chat feature (Mumble text messages) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers-extended-cc:subagent-driven-development (recommended) or superpowers-extended-cc:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** In-call text chat — send to your current channel, see incoming messages in a dedicated chat screen with an unread badge on the call screen.

**Architecture:** Mumble `TextMessage` (control channel). Receive routes through a new `SessionStateMachine.Events.onTextMessage` into a session-scoped chat log held by `MumbleManager` (in-memory, capped, cleared on disconnect); send goes out to the current channel and locally appends (Mumble doesn't echo your own message — verified in Task 1). Incoming HTML is stripped to plain text. UI is a `ChatScreen` reached from a chat icon on the call screen.

**Tech Stack:** Kotlin, protobuf (Mumble.proto `TextMessage`), kotlinx.coroutines StateFlow, Jetpack Compose (Material 3), JUnit.

**User decisions (already made):**
- Send scope: **current channel only** (DMs deferred).
- Rendering: **plain text, strip HTML** (rich HTML deferred).
- Persistence: **session-only, in-memory** (cleared on disconnect; capped ~200).
- Chat is only available during a call (it's a control-channel message).

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `app/src/test/.../integration/LiveServerIntegrationTest.kt` | echo/delivery probe | 1 |
| `mumble/ChatMessage.kt` (new) | `ChatMessage` data class + pure `stripHtml` | 2 |
| `mumble/protocol/SessionStateMachine.kt` | `TextMessage` receive (`Events.onTextMessage`) + `sendTextMessage` sender | 2 |
| `mumble/MumbleManager.kt` | chat log + unread + `sendChatMessage`/`markChatRead` + clear-on-disconnect | 3 |
| `ui/ChatScreen.kt` (new) | the chat screen | 4 |
| `ui/ActiveCallScreen.kt` | chat icon + unread badge in the top bar | 4 |
| `ui/DumbleApp.kt` | `showChat` nav + wiring | 4 |

Tasks keep the build green at each commit (Task 2 adds the plumbing without changing existing behavior; Task 3 consumes it; Task 4 is additive UI).

---

### Task 1: Verify TextMessage echo/delivery against Murmur (probe)

**Goal:** Settle the one load-bearing unknown — does the server echo your own channel `TextMessage` back to you (which would double-display), and does a same-channel peer receive it with `actor` = sender?

**Files:**
- Modify: `app/src/test/java/me/danielstiner/dumble/mumble/integration/LiveServerIntegrationTest.kt`

**Acceptance Criteria:**
- [ ] The probe connects two non-admin clients to the same (root) channel, has A send `TextMessage{channel_id=[ch], message}`, and prints: whether **B** (same channel) received it (with `actor`=A's session + the text) and whether **A** received its own message back (echo).
- [ ] Asserts B receives the message; prints (does not assert) the echo-to-sender result.

**Verify:** `MUMBLE_TEST_SERVER=127.0.0.1 ./gradlew :app:testDebugUnitTest --tests "*.LiveServerIntegrationTest.textMessageProbe"` (with dockerized Murmur up) → BUILD SUCCESSFUL; read the `CHAT-PROBE` lines in the test report.

**Steps:**

- [ ] **Step 1: Start the server** (if not running): `docker compose -f docs/dev/mumble-server/docker-compose.yml up -d`.

- [ ] **Step 2: Add the probe** to `LiveServerIntegrationTest.kt` (the `Harness` already has a `frameSpy` + `connectAndSync` from the UserStats probe):

```kotlin
    @Test fun textMessageProbe() = runBlocking {
        val a = Harness(host!!, port, password, forceTcp = false)
        val b = Harness(host!!, port, password, forceTcp = false)
        val aGot = java.util.concurrent.CopyOnWriteArrayList<MumbleProtos.TextMessage>()
        val bGot = java.util.concurrent.CopyOnWriteArrayList<MumbleProtos.TextMessage>()
        a.frameSpy = { f -> if (f.type == TcpMessageType.TextMessage.id) aGot.add(MumbleProtos.TextMessage.parseFrom(f.payload)) }
        b.frameSpy = { f -> if (f.type == TcpMessageType.TextMessage.id) bGot.add(MumbleProtos.TextMessage.parseFrom(f.payload)) }
        try {
            a.connectAndSync("chat-probe-a")
            b.connectAndSync("chat-probe-b")
            delay(500)
            val aSelf = a.model.state.value.sessionId!!
            val aChannel = a.model.state.value.users[aSelf]!!.channelId
            val msg = MumbleProtos.TextMessage.newBuilder().addChannelId(aChannel).setMessage("hello from a").build()
            aGot.clear(); bGot.clear()
            a.tcp.sendRaw(TcpMessageType.TextMessage, msg.toByteArray(), msg.toByteArray().size)
            delay(2_000)
            val bMsg = bGot.firstOrNull { it.message == "hello from a" }
            println("CHAT-PROBE aChannel=$aChannel aSelf=$aSelf bReceived=${bMsg != null} " +
                "bActor=${bMsg?.actor} echoToSender=${aGot.any { it.message == "hello from a" }}")
            assertNotNull("B (same channel) must receive A's message", bMsg)
        } finally { a.shutdown(); b.shutdown() }
    }
```

- [ ] **Step 3: Run** the probe (verify command above). Record from the `CHAT-PROBE` line: `echoToSender` (expected **false** → Task 3 locally appends) and `bActor` (expected = A's session).

- [ ] **Step 4: Commit.**

```bash
git add app/src/test/java/me/danielstiner/dumble/mumble/integration/LiveServerIntegrationTest.kt
git commit -m "test(chat): probe TextMessage echo/delivery against Murmur"
```

```json:metadata
{"files": ["app/src/test/java/me/danielstiner/dumble/mumble/integration/LiveServerIntegrationTest.kt"], "verifyCommand": "MUMBLE_TEST_SERVER=127.0.0.1 ./gradlew :app:testDebugUnitTest --tests \"*.LiveServerIntegrationTest.textMessageProbe\"", "acceptanceCriteria": ["probe prints echoToSender + bActor + bReceived", "asserts B receives A's message"], "modelTier": "standard"}
```

---

### Task 2: Chat protocol wiring + ChatMessage/stripHtml

**Goal:** The state machine can receive and send `TextMessage`s; a `ChatMessage` type and a pure `stripHtml` exist.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/mumble/ChatMessage.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/protocol/SessionStateMachine.kt`
- Test: `app/src/test/java/me/danielstiner/dumble/mumble/SessionStateMachineTest.kt`, and new `app/src/test/java/me/danielstiner/dumble/mumble/StripHtmlTest.kt`
- Modify (compile fix): `app/src/test/java/me/danielstiner/dumble/mumble/integration/LiveServerIntegrationTest.kt` — its `Harness` has a **third** `object : SessionStateMachine.Events` (~line 59) that MUST gain the new override or `app/src/test` won't compile.

**Acceptance Criteria:**
- [ ] `SessionStateMachine.Events` gains `onTextMessage(actor: Int, message: String)`; `onFrame` routes `TcpMessageType.TextMessage` to it (`actor` = `msg.actor` if present else 0).
- [ ] `SessionStateMachine.sendTextMessage(channelId: Int, text: String)` sends `TextMessage{channel_id=[channelId], message=text}`.
- [ ] `ChatMessage(sender, text, mine, timestampMs)` data class exists.
- [ ] `stripHtml` removes tags and unescapes `&amp; &lt; &gt; &quot; &#39; &nbsp;`, collapsing whitespace.

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*.SessionStateMachineTest" --tests "*.StripHtmlTest"` → pass.

**Steps:**

- [ ] **Step 1: Create `mumble/ChatMessage.kt`:**

```kotlin
package me.danielstiner.dumble.mumble

/** One chat line for the in-call chat log. [sender] is resolved at receive time so it stays correct
 *  even if that user later leaves; [mine] marks our own sent messages. */
data class ChatMessage(val sender: String, val text: String, val mine: Boolean, val timestampMs: Long)

private val TAG_RE = Regex("<[^>]*>")
private val WS_RE = Regex("\\s+")

/** Strip HTML tags and unescape common entities for plain-text chat display. Good enough for v1
 *  (Mumble messages "may be HTML"); rich rendering is a deferred follow-up. */
fun stripHtml(s: String): String =
    TAG_RE.replace(s, " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        .let { WS_RE.replace(it, " ") }
        .trim()
```

- [ ] **Step 2: Failing tests.** `StripHtmlTest.kt`:

```kotlin
package me.danielstiner.dumble.mumble

import org.junit.Assert.assertEquals
import org.junit.Test

class StripHtmlTest {
    @Test fun stripsTagsAndUnescapes() {
        assertEquals("hi there", stripHtml("<p>hi <b>there</b></p>"))
        assertEquals("a & b < c", stripHtml("a &amp; b &lt; c"))
        assertEquals("plain", stripHtml("plain"))
        assertEquals("x y", stripHtml("x   \n  y"))
    }
}
```

In `SessionStateMachineTest.kt`, add `onTextMessage` to `RecordingEvents` and two tests:

```kotlin
    // in RecordingEvents:
    var lastText: Pair<Int, String>? = null
    override fun onTextMessage(actor: Int, message: String) { lastText = actor to message }
```
```kotlin
    @Test fun onFrameTextMessageRoutesToEvents() {
        sm.start("dan", null)
        frame(TcpMessageType.TextMessage, MumbleProtos.TextMessage.newBuilder().setActor(7).setMessage("hi").build())
        assertEquals(7 to "hi", events.lastText)
    }

    @Test fun sendTextMessageTargetsChannel() {
        sm.start("dan", null)
        sm.sendTextMessage(3, "hello")
        val tm = channel.sent.last { it.first == TcpMessageType.TextMessage }.second as MumbleProtos.TextMessage
        assertEquals(listOf(3), tm.channelIdList); assertEquals("hello", tm.message)
    }
```

- [ ] **Step 3: Run → FAIL** (unresolved `onTextMessage`/`sendTextMessage`; `RecordingEvents` must implement the new interface method — that's why it's added in Step 2).

- [ ] **Step 4: Implement in `SessionStateMachine.kt`.** Add to the `Events` interface:

```kotlin
    interface Events {
        fun onCryptReady()
        fun onTcpRtt(rttMs: Double)
        fun onTunneledVoice(plaintext: ByteArray, len: Int, arrivalNanos: Long)
        fun onTextMessage(actor: Int, message: String)
    }
```

In `onFrame`'s `when`, before the `else`:

```kotlin
            TcpMessageType.TextMessage -> {
                val tm = MumbleProtos.TextMessage.parseFrom(frame.payload)
                events.onTextMessage(if (tm.hasActor()) tm.actor else 0, tm.message)
            }
```

Add the sender near `sendSelfMute`:

```kotlin
    /** Send a chat message to [channelId] (everyone in that channel). */
    fun sendTextMessage(channelId: Int, text: String) {
        channel.send(TcpMessageType.TextMessage,
            MumbleProtos.TextMessage.newBuilder().addChannelId(channelId).setMessage(text).build())
    }
```

**CRITICAL — update ALL three `Events` implementors** or `app/src/test` won't compile. Besides `RecordingEvents` (Step 2), the `Harness` in `LiveServerIntegrationTest.kt` (~line 71, right after its `override fun onTunneledVoice`) has its own `object : SessionStateMachine.Events` — add a no-op override there (Task 1's probe reads TextMessage via `frameSpy` on raw frames, not through `Events`):

```kotlin
                override fun onTextMessage(actor: Int, message: String) {}
```
(`MumbleManager`'s `events` object is the third implementor — handled in Task 3.)

- [ ] **Step 5: Run → PASS.** `./gradlew :app:testDebugUnitTest --tests "*.SessionStateMachineTest" --tests "*.StripHtmlTest"`

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/ChatMessage.kt \
        app/src/main/java/me/danielstiner/dumble/mumble/protocol/SessionStateMachine.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/SessionStateMachineTest.kt \
        app/src/test/java/me/danielstiner/dumble/mumble/StripHtmlTest.kt
git commit -m "feat(chat): TextMessage send/receive wiring + ChatMessage/stripHtml"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/ChatMessage.kt", "app/src/main/java/me/danielstiner/dumble/mumble/protocol/SessionStateMachine.kt", "app/src/test/java/me/danielstiner/dumble/mumble/SessionStateMachineTest.kt", "app/src/test/java/me/danielstiner/dumble/mumble/StripHtmlTest.kt", "app/src/test/java/me/danielstiner/dumble/mumble/integration/LiveServerIntegrationTest.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest --tests \"*.SessionStateMachineTest\" --tests \"*.StripHtmlTest\"", "acceptanceCriteria": ["Events.onTextMessage + onFrame routing", "sendTextMessage targets channel", "ChatMessage data class", "stripHtml strips tags + unescapes", "ALL 3 Events implementors updated (incl. LiveServerIntegrationTest Harness no-op)"], "modelTier": "standard"}
```

---

### Task 3: MumbleManager chat log

**Goal:** A session-scoped chat log with unread tracking; send appends locally; cleared on disconnect.

**Files:**
- Modify: `app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt`

**Acceptance Criteria:**
- [ ] `chat: StateFlow<List<ChatMessage>>` and `unreadChat: StateFlow<Int>` exposed; log capped at `CHAT_CAP` (200, oldest dropped).
- [ ] The `events` object's `onTextMessage` resolves `actor`→name (from the model; `0`→"Server", unknown→"#actor"), `stripHtml`s the text, appends `mine=false`, and bumps unread.
- [ ] `sendChatMessage(text)` resolves the current channel from the model, sends via `sm.sendTextMessage`, and **locally appends** `mine=true` (per Task 1: no server echo).
- [ ] `markChatRead()` zeroes unread; both chat + unread are cleared on `disconnect()`.
- [ ] Append + unread + markRead are `@Synchronized` (receive on the session-dispatcher thread, send/markRead on the UI thread).

**Verify:** `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (glue; no new unit test — the sender/receiver are unit-tested in Task 2, end-to-end covered by Task 1's probe path + on-device).

**Steps:**

- [ ] **Step 1: Add fields + const** near the other StateFlows (e.g. beside `_jitterStats`) and the `*_MS`/cap consts:

```kotlin
    private const val CHAT_CAP = 200
```
```kotlin
    private val _chat = MutableStateFlow<List<ChatMessage>>(emptyList())
    /** In-call chat log (session-scoped, capped, cleared on disconnect). */
    val chat: StateFlow<List<ChatMessage>> = _chat.asStateFlow()
    private val _unreadChat = MutableStateFlow(0)
    /** Unread chat count for the call-screen badge; zeroed by [markChatRead]. */
    val unreadChat: StateFlow<Int> = _unreadChat.asStateFlow()
```

- [ ] **Step 2: Add the synchronized append + public API** (manager-level, beside `setMuted` etc.):

```kotlin
    @Synchronized private fun appendChat(m: ChatMessage) {
        _chat.value = (_chat.value + m).let { if (it.size > CHAT_CAP) it.takeLast(CHAT_CAP) else it }
        if (!m.mine) _unreadChat.value = _unreadChat.value + 1
    }

    /** Send a chat message to our current channel and locally echo it (server doesn't echo to sender). */
    @Synchronized fun sendChatMessage(text: String) {
        val t = text.trim(); if (t.isEmpty()) return
        val m = model.state.value
        val ch = m.sessionId?.let { m.users[it]?.channelId } ?: return   // not in a channel yet → drop
        active?.sendTextMessage(ch, t)
        appendChat(ChatMessage(sender = "You", text = t, mine = true, timestampMs = System.currentTimeMillis()))
    }

    @Synchronized fun markChatRead() { _unreadChat.value = 0 }
```

- [ ] **Step 3: Implement `onTextMessage`** in the `events` anonymous object (beside `onTcpRtt`):

```kotlin
            override fun onTextMessage(actor: Int, message: String) {
                val name = if (actor == 0) "Server"
                    else model.state.value.users[actor]?.name ?: "#$actor"
                appendChat(ChatMessage(name, stripHtml(message), mine = false, System.currentTimeMillis()))
            }
```

- [ ] **Step 4: Add the `ActiveSession.sendTextMessage` passthrough** (beside `sendSelfMute`):

```kotlin
        fun sendTextMessage(channelId: Int, text: String) = sm.sendTextMessage(channelId, text)
```

- [ ] **Step 5: Clear on disconnect.** In `MumbleManager.disconnect()` (currently just
  `active?.shutdown(); active = null` — NOT `connect()`, which is where `model.reset()` lives), add:

```kotlin
        _chat.value = emptyList()
        _unreadChat.value = 0
```

- [ ] **Step 6: Run the full suite → PASS.** `./gradlew :app:testDebugUnitTest`

- [ ] **Step 7: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt
git commit -m "feat(chat): session-scoped chat log + unread in MumbleManager"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/mumble/MumbleManager.kt"], "verifyCommand": "./gradlew :app:testDebugUnitTest", "acceptanceCriteria": ["chat + unreadChat StateFlows, capped at 200", "onTextMessage resolves name + strips + appends + bumps unread", "sendChatMessage sends to current channel + locally appends", "markChatRead zeroes; cleared on disconnect", "synchronized append/unread/markRead"], "modelTier": "standard"}
```

---

### Task 4: Chat UI — screen + call-screen icon/badge + nav

**Goal:** A `ChatScreen` reached from a chat icon (with unread badge) on the call screen.

**Files:**
- Create: `app/src/main/java/me/danielstiner/dumble/ui/ChatScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt`
- Modify: `app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt`

**Acceptance Criteria:**
- [ ] `ChatScreen(messages, onSend, onBack)` shows a scrolling log (sender + text; own messages visually distinguished), auto-scrolls to newest, and has a text input + send button that calls `onSend` with non-blank text and clears the field.
- [ ] `ActiveCallScreen` shows a chat icon in the top bar (before Settings) with an unread badge when `unreadChat > 0`; tapping calls `onOpenChat`.
- [ ] `DumbleApp` renders a `showChat` branch (over the call screen), wires `onSend = MumbleManager.sendChatMessage`, calls `markChatRead()` while open, and passes `unreadChat`/`onOpenChat` to `ActiveCallScreen`.
- [ ] `./gradlew :app:assembleDebug` succeeds.

**Verify:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. (UI verified on-device.)

**Steps:**

- [ ] **Step 1: Create `ChatScreen.kt`:**

```kotlin
package me.danielstiner.dumble.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.danielstiner.dumble.mumble.ChatMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(messages: List<ChatMessage>, onSend: (String) -> Unit, onBack: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Chat") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            })
        },
        bottomBar = {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") }, maxLines = 4)
                IconButton(onClick = { if (draft.isNotBlank()) { onSend(draft); draft = "" } }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(messages) { m ->
                val who = if (m.mine) "You" else m.sender
                Text("$who: ${m.text}", modifier = Modifier.fillMaxWidth(),
                    textAlign = if (m.mine) TextAlign.End else TextAlign.Start)
            }
        }
    }
}
```

- [ ] **Step 2: Call-screen chat icon + badge (`ActiveCallScreen.kt`).** Add params `onOpenChat: () -> Unit, unreadChat: Int,` to the signature. Replace the `actions = { ... }` block:

```kotlin
                actions = {
                    IconButton(onClick = onOpenChat) {
                        BadgedBox(badge = { if (unreadChat > 0) Badge { Text("$unreadChat") } }) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat")
                        }
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Tune, "Settings") }
                },
```
Add imports: `androidx.compose.material.icons.automirrored.filled.Chat`, `androidx.compose.material3.Badge`, `androidx.compose.material3.BadgedBox` (`Badge` is already imported for the deaf/mute badges — verify; add only what's missing). Update the `@Preview` `ActiveCallScreen(...)` call to pass `onOpenChat = {}, unreadChat = 0`.

- [ ] **Step 3: Wire `DumbleApp.kt`.** Collect the flows near the others:

```kotlin
    val chat by MumbleManager.chat.collectAsStateWithLifecycle()
    val unreadChat by MumbleManager.unreadChat.collectAsStateWithLifecycle()
```
Add state beside `showSettings`:

```kotlin
    var showChat by remember { mutableStateOf(false) }
```
**Reset it when the call ends** so it can't leak into the next call. In the existing
`LaunchedEffect(state)` block that sets `connectedSince` (~DumbleApp.kt:97-101), also clear the flag:

```kotlin
    LaunchedEffect(state) {
        connectedSince = if (state is ConnectionState.Synchronized) { connectedSince ?: System.currentTimeMillis() } else null
        if (state !is ConnectionState.Synchronized) showChat = false   // NEW: don't reopen chat on the next call
    }
```
Add a branch **before** `inCall && !showSettings` (so it renders over the call screen):

```kotlin
        showChat && inCall -> {
            BackHandler { showChat = false }
            LaunchedEffect(chat.size) { MumbleManager.markChatRead() }
            ChatScreen(messages = chat, onSend = { MumbleManager.sendChatMessage(it) },
                onBack = { showChat = false })
        }
```
In the `ActiveCallScreen(...)` call, add:

```kotlin
                onOpenChat = { showChat = true },
                unreadChat = unreadChat,
```

- [ ] **Step 4: Build → PASS.** `./gradlew :app:assembleDebug`

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/me/danielstiner/dumble/ui/ChatScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt \
        app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt
git commit -m "feat(chat): ChatScreen + call-screen chat icon/unread badge + nav"
```

```json:metadata
{"files": ["app/src/main/java/me/danielstiner/dumble/ui/ChatScreen.kt", "app/src/main/java/me/danielstiner/dumble/ui/ActiveCallScreen.kt", "app/src/main/java/me/danielstiner/dumble/ui/DumbleApp.kt"], "verifyCommand": "./gradlew :app:assembleDebug", "acceptanceCriteria": ["ChatScreen: scrolling log + input + send, auto-scroll", "call-screen chat icon + unread badge", "showChat nav branch + markChatRead + wiring", "assembleDebug succeeds"], "modelTier": "standard"}
```

---

## Post-tasks
- Update `docs/TODO.md`: mark "Add chat feature" done (v1: channel chat, plain-text, session-only); note deferred follow-ups (DMs, HTML rendering, persistence).
- On-device (Dan's batch): connect two clients (or one Drumble + one desktop Mumble in the same channel); send both ways; confirm messages appear once (no duplicate/echo), sender names resolve, HTML from a desktop peer shows as readable text, and the unread badge works.
- Tear down the docker Murmur when done (`docker compose … down`).
