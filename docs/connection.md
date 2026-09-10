# Connection

One TLS connection carries the control protocol and, until a UDP path proves itself, our voice;
a UDP socket beside it carries voice both ways once one does. `MumbleConnection` coordinates
it — the blocking TLS connect (where trust is decided), the protocol session that follows, the
choice of transport for voice, and the lifecycle of the audio pipelines and the platform call —
unified into one `status` flow the UI observes. Its audio-capture half is in `docs/capture.md`;
this doc covers the connection itself. Details live in the code's comments; what follows is the
structure and the trade-offs that shaped it.

```
UI ─► Connection (interface)
          │
    MumbleConnection                    one live Session driving one Link, generation-guarded
          ├─► Link                      one TLS connect; replaced under the session on a reconnect
          │     ├─► MumbleTcpTransport ─► SSLSocket ─────────┐
          │     │      trust: MumbleTrustManager + PinStore   ├─► server
          │     ├─► MumbleUdpTransport ─► DatagramChannel ───┘
          │     │      crypt: CryptState, keyed by CryptSetup; path: VoicePath, proven by the ping
          │     └─► SessionStateMachine  handshake, pings, channel tree, chat, the cipher
          └─► audio + platform call     docs/capture.md, docs/playout.md
```

**Sessions, links and generations.** Only one session is live. Each `connect()` bumps a
generation and builds a `Session` — endpoint, credentials, the platform call, the receiver, and
capture state as one unit — whose driver coroutine opens one `Link`: the TLS transport, the state
machine on it, the UDP socket keyed by that session's cipher, and the collectors that republish
its flows. The split is what a reconnect needs: a link can be replaced under a session without
ending the call. The generation exists because a blocking handshake cannot be preempted: a
superseded link can complete late, so every flow write is generation-checked under the same lock
that bumps, turning late writes into no-ops instead of corruption; a link's own flows are checked
against the link's identity as well. Sessions end two deliberately different ways:
supersede/disconnect clears every published flow atomically with the bump, while a failed
connect or a dying link retires the session without clearing — the terminal status is what the
user is looking at. A trust prompt retires its session the same way but keeps it aside for
`trustAndConnect()` to reconnect from.

**Reconnect.** A link that dies after it synchronized is replaced under the same session: the
driver classifies the failure on the server's reject type (a name still held by our own ghost is
retried for the 45 s murmur takes to reap one, after which the name is someone else's and the
rejection is reported as it stands; every other rejection and a too-old server are final at
once), publishes `Reconnecting`, closes the dead link, and opens replacements on a ladder of 0,
1, 2, 4, 8, 16, 30, 30… seconds until one synchronizes or two minutes of reconnecting have
passed. A link counts as working once it has been synchronized for 30 s; losing one starts the
ladder and the deadline over, losing one that never got that far continues the outage in
progress — with the time that link spent synchronized handed back to the deadline, since it was
not spent reconnecting — so a path that dies every few seconds gives up in two minutes of
outage rather than rejoining forever. Whether a link ever synchronized is the state machine's
own stamp, taken where the transition happens rather than read from a collector on a conflating
flow, so a link that synchronized and died at once is still replaced. The replacement's
flows are wired only after it synchronizes, and the dead link's flows are frozen at its close, so
a late reduction on it cannot land under the replacement's session. The deadline
bounds when an attempt may start, not how long one may run, so a connect that hangs until its
socket timeout can finish past it. The deadline is measured on the boot clock while the rungs
wait on `delay`, which stops with the CPU; the two disagree only over time spent suspended, and
the ladder cannot be suspended through. Chat rides across the swap; the platform call, the
receiver and the capture session belong to the session and never notice — which is also what
rules the suspend out, since audioserver holds a partial wakelock for as long as those streams
are open, the outage included. The first link of a session is
never retried: its failure is the connect failing, and the connect form shows it. A trust prompt
on a reconnect (the server's certificate changed) retires the session with the prompt up and keeps
it aside for `trustAndConnect()`, as a fresh connect does.

**Transport** (`net/MumbleTcpTransport`). Connect-once per instance; reconnection is a new
instance, so no teardown state can leak between links. One reader coroutine delivers frames,
and its `finally` is the sole delivery point of `onClosed` — exactly once, never nested inside
`onFrame`, so listeners need no locking. The send queue is deliberately small: this is a
low-volume control channel, and a larger buffer would only let a stalled socket hide longer.

**UDP voice** (`net/MumbleUdpTransport`). A connected `DatagramChannel` aimed at the control
connection's own remote address, with `CryptState` sealing and opening every datagram, and one
thread blocking in read. Opened as soon as the control connection is up and closed with the
link. A socket that cannot be opened costs the session nothing: the server never learns an
address and keeps the downlink on the tunnel. The state machine owns the cipher, because
`CryptSetup` is a control message, and fires the UDP ping at keying and on the TCP ping's own
ticker. Receiving is unconditional: the server pushes a client's downlink over UDP from its
first ping on, whether or not that client has ever transmitted, so a socket that only pinged
would deafen a listener (`docs/mumble-protocol.md`, Voice framing).

**Which transport carries our voice** (`net/VoicePath`). Voice starts tunneled and earns UDP on
the ping alone: a reply proves both directions at once, and the server answers the ping over UDP
whichever path voice is on. One reply promotes. The transport's report of two unanswered pings
demotes, as does a datagram the socket refuses, which goes through the tunnel in the same call.
After a demote it takes two replies to promote again, so a path answering half its pings settles
on the tunnel instead of flapping, and there is no cap on demotions. The cipher's counters are
never read, since our encrypt counter advances whether or not a datagram lands. The label and
the round trip are one record, so a demote clears both at once. Every accepted reply also goes
into the average our TCP ping reports, which is what other clients' sheets read about us
(`docs/mumble-protocol.md`, Ping).

**What a failure looks like.** The server binds a client's UDP address once and never re-learns
it, and keeps sending there until a `UDPTunnel` frame from that client clears its per-user flag,
which a client that only listens never sends. So a NAT that rebinds our port, or a socket that
dies, would leave the downlink dead with the control channel reading healthy. The transport
judges each ping when the next is sent, an interval later, and reports two unanswered in a row
once per outage; the connection demotes the path and tunnels one ping, since the server clears
the flag on any frame that passes its length check, before decoding it, so no peer hears a blip.
A rebound port stays on the tunnel for the session, since the server ignores the new one. A
transient loss costs two intervals to demote and two replies to come back, and only a UDP audio
packet sets the server's flag again, so a listener who never speaks keeps a tunneled downlink
after any demote. A change of network kills the TLS socket, which ends the session; a reconnect
builds every piece afresh. Pinned against a real server in `LiveServerIntegrationTest`.

**Trust** (`net/MumbleTrustManager` + `PinStore`). Pins are SHA-256 of the whole leaf certificate,
keyed by the endpoint as the user typed it. Pin first, certificate authority second — ordered the
other way, any certificate a trusted authority issued for the host would silently override the
user's explicit "this exact server", which is the case pinning exists to stop. (The desktop client
orders these the other way; we match its behaviour elsewhere, not here.) The cost is that a
legitimately re-certificated server stops connecting until re-accepted — the correct prompt, since
the certificate really did change. No pin plus an invalid chain (most Mumble servers are
self-signed) stops the handshake with the fingerprint so the UI can offer to accept it;
`trustAndConnect()` stores the pin and reconnects. Host-name verification runs only on the
authority-validated path — a pinned certificate is already bound to its endpoint, and stock Mumble
certificates carry no usable subject.

**Client certificate** (`net/ClientIdentity` + `ClientIdentityStore`). Every handshake offers one
self-signed RSA-3072 certificate, generated in the background at first launch and kept as an unencrypted PKCS#12
(`identity.p12` under `filesDir`, inside Auto Backup's default scope) so it follows a reinstall and
can later be exported to desktop Mumble, which reads that format and only RSA. Murmur asks for a
certificate, accepts any, verified or not, and keeps the SHA-1 of the leaf as the session's hash.
Until a server registers the user that hash decides one thing: a connection under a name another
session still holds is accepted from a new address only when the hashes match, and the old session
is then kicked at once ("Disconnecting ghost"); without it a reconnect after a network change is
refused until the server's 30 s timeout reaps the old session. One identity for every server, as
the desktop has. Under TLS 1.2 it crosses the wire in the clear, as it does for every Mumble client;
TLS 1.3 encrypts it. In both versions the client sends its certificate only after the server's
chain has passed the trust manager, so a server the user has not yet accepted never sees the hash.
A file that no longer decodes fails every connect, with the reason on the connect screen, rather
than being replaced: the bytes stay for a fix to recover, and clearing the app's data starts over.

**Protocol** (`protocol/SessionStateMachine`). `Version` + `Authenticate`, then `ServerSync` under
its own deadline (the transport's timeout bounds only the socket connect). Transitions settle by
compare-and-set; first failure wins. Servers below 1.5 are refused rather than joined without
voice: a 1.4 server parses protobuf voice as malformed legacy CELT and silently drops every frame,
and voice is the point of connecting. After sync, pings ride the boot clock (it counts doze), and
what is published is the instant of the last server reply, not an age — an age is stale the moment
it is published, and a dozed device fires no tick to refresh it. Tunneled voice bypasses the flows
as a callback: `StateFlow` conflates, and a dropped emission would be dropped audio.

Invariants are pinned by `MumbleConnectionTest`, `TelecomLifecycleChaosTest`,
`MumbleTcpTransportTest`, `MumbleUdpTransportTest`, `VoicePathTest`, `MumbleTrustManagerTest`,
and `SessionStateMachineTest`.
