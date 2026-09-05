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
    MumbleConnection                    one live Attempt, generation-guarded
          ├─► MumbleTcpTransport ─► SSLSocket ─────────┐
          │      trust: MumbleTrustManager + PinStore   ├─► server
          ├─► MumbleUdpTransport ─► DatagramChannel ───┘
          │      crypt: CryptState, keyed by CryptSetup; path: VoicePath, proven by the ping
          ├─► SessionStateMachine       handshake, pings, channel tree, chat, the cipher
          └─► audio + platform call     docs/capture.md, docs/playout.md
```

**Attempts and generations.** Only one connection is live. Each `connect()` bumps a generation and
builds an `Attempt` — endpoint, transport, state machine, receiver, and capture state as one unit.
The model exists because a blocking handshake cannot be preempted: a superseded attempt can
complete late, so every flow write is generation-checked under the same lock that bumps, turning
late writes into no-ops instead of corruption. Attempts end two deliberately different ways:
supersede/disconnect clears every published flow atomically with the bump, while a session that
fails on its own retires without clearing — the terminal `Error` is what the user is looking at.

**Transport** (`net/MumbleTcpTransport`). Connect-once per instance; reconnection is a new
instance, so no teardown state can leak between attempts. One reader coroutine delivers frames,
and its `finally` is the sole delivery point of `onClosed` — exactly once, never nested inside
`onFrame`, so listeners need no locking. The send queue is deliberately small: this is a
low-volume control channel, and a larger buffer would only let a stalled socket hide longer.

**UDP voice** (`net/MumbleUdpTransport`). A connected `DatagramChannel` aimed at the control
connection's own remote address, with `CryptState` sealing and opening every datagram, and one
thread blocking in read. Opened as soon as the control connection is up and closed with the
attempt. A socket that cannot be opened costs the session nothing: the server never learns an
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
the round trip are one record, so a demote clears both at once.

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
