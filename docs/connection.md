# Connection

One TLS connection carries everything: protobuf control messages and tunneled voice on the same
socket. `MumbleConnection` coordinates it — the blocking TLS connect (where trust is decided), the
protocol session that follows, and the lifecycle of the audio pipelines and the platform call —
unified into one `status` flow the UI observes. Its audio-capture half is in `docs/capture.md`;
this doc covers the connection itself. Details live in the code's comments; what follows is the
structure and the trade-offs that shaped it.

```
UI ─► Connection (interface)
          │
    MumbleConnection                    one live Attempt, generation-guarded
          ├─► MumbleTcpTransport ─► SSLSocket ─► server
          │      trust: MumbleTrustManager + PinStore
          ├─► SessionStateMachine       handshake, pings, channel tree, chat
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
`MumbleTcpTransportTest`, `MumbleTrustManagerTest`, and `SessionStateMachineTest`.
