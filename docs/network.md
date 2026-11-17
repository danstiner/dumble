# Network

How this app reaches a server and stays there. The first half covers a connection as a whole:
sessions, links and reconnecting. Then [TCP](#tcp-the-control-connection) covers the TLS control
connection and trust, and [UDP](#udp-the-low-latency-voice-path) covers the voice socket. The bytes
on the wire are in [mumble-protocol.md](mumble-protocol.md).

## Goals

- **A call outlives its socket.** A network change or short outage replaces the connection
  without ending the call or reopening audio.
- **Failures are distinct and say why:** a dead path, a rejection, a kick, a changed certificate.
- **The user decides which server to trust,** and nothing overrides that silently.
- **Voice uses UDP when UDP works and TCP otherwise,** with no setting and no lost downlink.

## Design

```
UI ─► Connection (interface)
          │
    MumbleConnection                    one live Session, guarded by a generation
          ├─► Link                      one TLS connect; replaced under the session on a reconnect
          │     ├─► MumbleTcpTransport ─► SSLSocket ─────────┐
          │     │      trust: MumbleTrustManager + PinStore   ├─► server
          │     ├─► MumbleUdpTransport ─► DatagramChannel ───┘
          │     │      cipher: CryptState; path: VoicePath
          │     └─► SessionStateMachine  handshake, pings, channel tree, chat, cipher keys
          └─► audio and the platform call     capture.md, playout.md
```

## Decisions

| Decision | Why | Cost |
|---|---|---|
| A session is split from its link | Audio and the platform call belong to the session, so replacing a link does not interrupt them. | Two lifetimes to guard instead of one. |
| Late writes are discarded by generation | A superseded connect can still complete after its session was replaced; teardown cannot always stop a handshake in flight. | Every flow write takes one lock. |
| Reconnecting gives up after 2 minutes | Long enough for an elevator or a tunnel; short enough that a dead server does not hold the phone in call mode. | A longer outage needs a manual reconnect. |
| A link with no ping reply for 15 to 20 s is replaced | A dead path may never produce a socket error. | A path stalled that long but alive is torn down too. |
| The old link is closed before the new one is dialed | The old link would read its own ghost kick as the server removing us, and end the session. | A moment with no link on every handover. |
| Voice moves to UDP on the ping alone | A reply proves both directions, and replies keep arriving while voice is tunneled. | Voice can stay on a lossy UDP path: only two unanswered pings in a row demote it. |
| The pin is checked before the certificate authorities | Otherwise any authority-issued certificate for the host overrides the user's choice of one exact server. Desktop Mumble uses the other order. | A server that renews its certificate must be accepted again. |
| One client certificate for every server, stored unencrypted and backed up | It follows a reinstall, is in the format desktop Mumble reads, and lets the server replace our old session on a reconnect. | The key is protected only by the app sandbox, and every server sees the same certificate. |
| The local network permission is requested after a block, not before | Android decides by destination address, which the host text does not reveal. | The first connect to a LAN server fails once before the prompt. |

## Sessions, links and generations

- A **session** is one `connect()`: the endpoint, the credentials, the platform call, the voice
  receiver and the capture state. One is live at a time.
- A **link** is one TLS connect and what lives exactly as long as it: the TCP transport, the
  state machine, the UDP socket keyed by that link's cipher, the `VoicePath`, and the collectors
  that republish its flows.
- The **generation** is a counter that `connect()` bumps. Every flow write is checked under the
  lock that bumps it, so a superseded link finishing late writes nothing. A session's flows are
  checked against the generation, the call's hold and routes against the live session, and a
  link's flows against the link's identity as well.

`connect()` retires the old session, publishes the new one and starts its call under one lock, so
starts reach the platform call in generation order. A start supersedes the call before it and
keeps its audio mode and route.

A session ends in one of two ways:

- **Superseded or disconnected.** Every published flow is cleared in the same step as the bump.
- **Failed.** The session is retired and its flows are left alone, because the final status is
  what the user is looking at. The call's hold and routes are cleared regardless. A trust prompt
  retires its session this way too, and keeps it for `trustAndConnect()`.

## Reconnect

A link that dies after it synchronized is replaced under the same session. The driver publishes
`Reconnecting`, closes the dead link and dials replacements until one synchronizes or the budget
runs out. The first link of a session is never retried: its failure is the connect failing.

| | Value |
|---|---|
| Backoff before each attempt | 0, 1, 2, 4, 8, 16, 30, 30… s, the last one clamped to what is left of the budget |
| Budget (`GIVE_UP_AFTER`) | 2 min of reconnecting |
| Healthy link (`HEALTHY_AFTER`) | synchronized for 30 s |
| Ping timeout (`DEGRADED_PING_AGE`) | no reply for 3 ping intervals, checked as each ping is sent |
| Ghost retry (`GHOST_REAP`) | 45 s |

- **Healthy links reset the outage.** Losing a link that stayed synchronized for 30 s starts the
  backoff and the budget over. Losing one that did not continues the outage in progress, with the
  time it spent synchronized added back to the budget. So a path that dies every few seconds
  gives up after two minutes of outage.
- **The budget bounds when an attempt may start,** not how long it runs. A connect that hangs to
  its socket timeout can finish after the deadline.
- **Clocks.** The budget is on the boot clock; the backoff waits on `delay`, which stops when the
  CPU sleeps. They disagree only across a suspend, which the call's audio streams prevent by
  holding a partial wakelock.
- **Whether a link synchronized** is stamped by the state machine at the transition, so a link
  that synchronized and died within one emission is still replaced.
- **Flows.** A replacement's flows are wired once it synchronizes and a dead link's are frozen
  when it closes. Chat history carries across.
- **Ghosts.** Murmur drops a silent client at 30 s, so after a ping timeout the replacement is
  usually dialed while our old session, the ghost, still holds the name.

### What ends the session instead

- **A rejection,** classified by the server's reject type. "Username in use" is retried for 45 s,
  long enough for murmur to drop our ghost, which it does after 30 s of silence. Every other
  rejection, and a server older than 1.5, ends the session at once.
- **A kick or a ban.** Murmur sends the removal before closing the socket, and the session ends with
  its reason. Another device connecting on our certificate kicks us with the same message, so two
  devices never kick each other back and forth.
- **A trust prompt on a replacement.** The session is retired and kept for `trustAndConnect()`, as
  on a first connect.
- **A local network block on a replacement.** It waits on the user.

### Network changes

- A link is dialed on the default network and remembers which one.
- When a change of the default network finds a link's own network gone, the link is closed
  without waiting for the socket to notice. A link on a non-default network gets no such
  event when that network later disappears; the ping timeout catches it.
- An attempt in flight is abandoned on any change of the default network. Closing a transport
  aborts its connect, so nothing runs out to a timeout.
- A link whose network merely stopped being the default is left alone. With a LAN server on a
  WiFi that lost its uplink, it is the only link that reaches the server.
- The first attempt after a change is immediate, unless the last dial was under a second ago, so
  a flapping network cannot cause a connect storm.

## TCP: the control connection

Each link has one TLS connection. It carries every control message, and our voice too until UDP
has proven itself.

### TLS transport

`net/MumbleTcpTransport`. An instance connects once; a reconnect is a new instance, so no teardown
state leaks between links. One reader coroutine delivers frames, and its `finally` is the only
caller of `onClosed`: exactly once, never from inside `onFrame`, so listeners need no locking. The
send queue is small on purpose (32 frames): a larger buffer would only hide a stalled socket for
longer. Tunneled voice goes through the same queue, and a full queue refuses the packet.

### Trust

`net/MumbleTrustManager` and `PinStore`. A pin is the SHA-256 of the whole leaf certificate, keyed
by the endpoint's host and port in canonical form.

- **Pinned:** the certificate must match the pin. Nothing else is consulted.
- **No pin, valid chain:** accepted, with the host name verified. Host names are checked only
  here: a pinned certificate is already bound to its endpoint, and stock Mumble certificates have
  no usable subject.
- **No pin, invalid chain,** which is most Mumble servers since they are self-signed: the
  handshake stops and hands the fingerprint to the UI. `trustAndConnect()` stores the pin and
  reconnects.

### Client certificate

`net/ClientIdentity` and `ClientIdentityStore`. Every handshake offers one self-signed RSA-3072
certificate, generated in the background at first launch.

- **Storage.** An unencrypted PKCS#12 file, `identity.p12` under `filesDir`, inside Auto Backup's
  default scope. Desktop Mumble reads that format, and only RSA keys.
- **What the server does with it.** Murmur asks for a certificate, accepts any, and keeps the
  SHA-1 of the leaf as the session's hash. Until a server registers the user, the hash decides one
  thing: a connect under a name another session still holds, from a new address, is accepted only
  when the hashes match, and the old session is kicked at once ("Disconnecting ghost"). Without
  it, a reconnect after a network change is refused until murmur's 30 s timeout drops the old
  session.
- **Privacy.** Under TLS 1.2 the certificate crosses the wire in the clear, as for every Mumble
  client. TLS 1.3 encrypts it. Either way it is sent only after the server's chain has passed the
  trust manager, so a server the user has not accepted never sees the hash.
- **A file that will not decode fails every connect,** with the reason on the connect screen. It
  is not replaced: the bytes stay recoverable, and clearing the app's data starts over.

### Local network access

Android can block a socket to a local address when an app lacks the local network permission,
and to Java the blocked connect looks like any timeout. So from API 37 the connect runs beside a
watcher that reads `android_getnetworkblockedreason` through a duplicate of the socket's
descriptor and aborts on the first block. The connect screen asks for the permission from that
error.

Measured: an on-link address is blocked, and a private address reached through the gateway is
not. A public name can resolve to a local address. A `.local` name needs no special case: its
lookup succeeds without the permission and its socket is then blocked like any other.

### Protocol session

`protocol/SessionStateMachine`. It sends `Version` and `Authenticate`, then waits for `ServerSync`
under its own 15 s deadline, since the transport's own timeouts (10 s each for the socket connect
and the TLS handshake) end there. State transitions settle by compare-and-set, and the first failure
wins.

- Pings run on the boot clock, which counts time in doze.
- The published value is the instant of the last server reply, not its age. An age is stale as
  soon as it is published, and a dozing device fires no tick to refresh it.
- Tunneled voice is delivered by callback, not through a flow. A `StateFlow` conflates, and a
  dropped emission would be dropped audio.

## UDP: the low-latency voice path

A UDP socket beside the control connection carries voice once a ping has round-tripped on it. It
is the low-latency path: a lost datagram delays nothing behind it, where TCP holds every later
packet back until the lost one is resent. It is optional: without it the session is complete,
with voice in the tunnel.

### UDP voice

`net/MumbleUdpTransport`. A connected `DatagramChannel` aimed at the control connection's remote
address, `CryptState` encrypting and decrypting every datagram, and one thread blocked in read.

- Opened as soon as the control connection is up, closed with the link.
- A socket that cannot be opened costs nothing. The server never learns a UDP address and keeps
  our downlink on the tunnel.
- The state machine owns the cipher, because `CryptSetup` is a control message. It sends the UDP
  ping when the cipher is keyed and then on the TCP ping's ticker.
- Receiving is unconditional. The server sends a client's downlink over UDP from that client's
  first ping, whether or not it has ever transmitted, so a socket that only pinged would hear
  nothing ([mumble-protocol.md](mumble-protocol.md), Voice framing).

### Which transport carries our voice

`net/VoicePath`. Each link is in one of two states: our outgoing voice is *tunneled*, sent as
`UDPTunnel` frames on the TLS connection, or *on UDP*. A link starts tunneled.
`MumbleConnection.sendVoice` reads the state for every packet, and the UI shows it from
`Connection.voicePath`.

- **Promote** switches our outgoing voice from the tunnel to UDP, from the next packet on.
- **Demote** switches it back to the tunnel, and clears the round trip the UI was showing. The
  first tunneled frame after a demotion also makes the server move our downlink to the tunnel
  ([below](#when-udp-stops-working)).

Neither changes what we accept: both sockets feed the same receiver at all times, and which one
the server sends our downlink on is the server's choice.

What causes each:

- **Promote:** one UDP ping reply at the start of a link. After a demotion it takes two, which
  damps flapping. The server answers a UDP ping over UDP whichever path voice is on, so the
  evidence keeps arriving while tunneled.
- **Demote:** the transport reports two pings in a row unanswered, or the socket refuses a
  datagram. The refused datagram goes through the tunnel in the same call. Demotions are not
  capped.
- The cipher's counters are never consulted: our encrypt counter advances whether or not a
  datagram arrives.
- The path and its round trip are one record, so a demotion clears both together.
- Every accepted reply also feeds the average our TCP ping reports, which is what other clients
  show about us ([mumble-protocol.md](mumble-protocol.md), Ping).

### When UDP stops working

The server binds a client's UDP address once and never learns a new one. It sends the downlink
there while a per-user flag is set, and only a `UDPTunnel` frame from that client clears the flag.
A client that only listens never sends one. So a NAT that rebinds our port, or a socket that dies,
would leave us deaf while the control channel looks healthy.

- The transport judges each ping when it sends the next one, and reports two in a row unanswered
  once per outage.
- The connection then demotes the path and tunnels one ping. The server clears the flag on any
  tunneled frame that passes its length check, before decoding it, so a ping moves the downlink
  to the tunnel and no peer hears anything.
- A rebound port stays on the tunnel for the rest of the link, because the server ignores the
  new port.
- A brief loss costs two ping intervals to demote and two replies to come back. Only a UDP audio
  packet from us sets the server's flag again, so a listener who never speaks keeps a tunneled
  downlink after any demotion.

## Tests

`MumbleConnectionTest`, `MumbleTcpTransportTest`, `MumbleUdpTransportTest`, `VoicePathTest`,
`MumbleTrustManagerTest`, `SessionStateMachineTest`, and `LiveServerIntegrationTest` against a
real server.
