# Network

How this app reaches a server and stays there. The first half covers sessions, connections and
reconnecting. Then [TCP](#tcp-the-control-connection) covers the TLS control connection and trust,
and [UDP](#udp-the-low-latency-voice-path) covers the voice socket. The bytes on the wire are in
[mumble-protocol.md](mumble-protocol.md).

## Goals

- **A call outlives its socket.** A network change or short outage replaces the connection
  without ending the call or reopening audio.
- **Failures are distinct and say why:** a dead path, a rejection, a kick, a changed certificate.
- **The user decides which server to trust,** and nothing overrides that silently.
- **Voice uses UDP when UDP works and TCP otherwise,** with no setting, and without going deaf when UDP stops working.

## Design

```
UI ─► AppState (interface)
          │
    AppStateImpl                        one live Session; a replaced one can no longer publish
          ├─► Connection                one TLS connect; replaced under the session on a reconnect
          │     ├─► MumbleTcpTransport ─► SSLSocket ─────────┐
          │     │      trust: MumbleTrustManager + PinStore   ├─► server
          │     ├─► MumbleUdpTransport ─► DatagramChannel ───┘
          │     │      cipher: CryptState; path: VoicePath
          │     └─► ConnectionStateMachine  handshake, pings, channel tree, chat, cipher keys
          └─► audio and the platform call     capture.md, playout.md
```

## Decisions

| Decision | Why | Cost |
|---|---|---|
| A session is split from its connection | Audio and the platform call belong to the session, so replacing a connection does not interrupt them. | Two lifetimes to guard instead of one. |
| A replaced session's late writes are discarded | A superseded connect can still complete after its session was replaced; teardown cannot always stop a handshake in flight. | Every flow write takes one lock. |
| Reconnecting gives up after 2 minutes | Long enough for an elevator or a tunnel; short enough that a dead server does not hold the phone in call mode. | A longer outage needs a manual reconnect. |
| A connection with no ping reply for 15 to 20 s is replaced | A dead path may never produce a socket error. | A path stalled that long but alive is torn down too. |
| The old connection is closed before the new one is dialed | The old connection would read its own ghost kick as the server removing us, and end the session. | A moment with no connection on every handover. |
| Voice moves to UDP on the ping alone | A reply proves both directions, and replies keep arriving while voice is on TCP. | Voice can stay on a lossy UDP path: it takes two unanswered pings in a row to move it back to TCP. |
| The pin is checked before the certificate authorities | Otherwise any authority-issued certificate for the host overrides the user's choice of one exact server. Desktop Mumble uses the other order. | A server that renews its certificate must be accepted again. |
| One client certificate for every server, stored unencrypted and backed up | It follows a reinstall, is in the format desktop Mumble reads, and lets the server replace our old session on a reconnect. | The key is protected only by the app sandbox, and every server sees the same certificate. |
| The local network permission is requested after a block, not before | Android decides by destination address, which the host text does not reveal. | The first connect to a LAN server fails once before the prompt. |

## Sessions and connections

- A **session** is one `connect()`: the endpoint, the credentials, the platform call, the voice
  receiver and the capture state. One is live at a time.
- A **connection** is one TLS connect and what lives exactly as long as it: the TCP transport, the
  state machine, the UDP socket keyed by that connection's cipher, the `VoicePath`, and the
  collectors that republish its flows.
- A session writes through a **publisher** that the next `connect()` or `disconnect()` closes.
  Its writes are checked under the one lock that closes it, so a superseded connection finishing
  late writes nothing. A session's flows are checked against its publisher, the call's hold and
  routes against the live session, and a connection's flows against the connection's identity as
  well.

`connect()` retires the old session, publishes the new one and starts its call under one lock, so
starts reach the platform call in the order of the connects. A start supersedes the call before it and
keeps its audio mode and route.

A session ends in one of two ways:

- **Superseded or disconnected.** Its publisher is closed and every published flow is cleared, in
  one step.
- **Failed.** The session is retired, its publisher stays open, and its flows are left alone, because
  the final status is what the user is looking at. The call's hold and routes are cleared
  regardless. A trust prompt retires its session this way too, and keeps it for
  `trustAndConnect()`.

## Reconnect

A connection that dies after it synchronized is replaced under the same session. The driver
publishes `Reconnecting`, closes the dead connection and dials replacements until one synchronizes
or the budget runs out. The first connection of a session is never retried: its failure is the
connect failing.

| | Value |
|---|---|
| Backoff before each attempt | 0, 1, 2, 4, 8, 16, 30, 30… s, the last one clamped to what is left of the budget |
| Budget (`GIVE_UP_AFTER`) | 2 min of reconnecting |
| Healthy connection (`HEALTHY_AFTER`) | synchronized for 30 s |
| Ping timeout (`DEGRADED_PING_AGE`) | no reply for 3 ping intervals, checked as each ping is sent |
| Ghost retry (`GHOST_REAP`) | 45 s |

- **Healthy connections reset the outage.** Losing a connection that stayed synchronized for 30 s
  starts the backoff and the budget over. Losing one that did not continues the outage in
  progress, with the time it spent synchronized added back to the budget. So a path that dies
  every few seconds gives up after two minutes of outage.
- **The budget bounds when an attempt may start,** not how long it runs. A connect that hangs to
  its socket timeout can finish after the deadline.
- **Clocks.** The budget is on the boot clock; the backoff waits on `delay`, which stops when the
  CPU sleeps. They disagree only across a suspend, which the call's audio streams prevent by
  holding a partial wakelock.
- **Whether a connection synchronized** is stamped by the state machine at the transition, so a
  connection that synchronized and died within one emission is still replaced.
- **Flows.** A replacement's flows are wired once it synchronizes and a dead connection's are frozen
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

- A connection is dialed on the default network and remembers which one.
- When a change of the default network finds a connection's own network gone, the connection is
  closed without waiting for the socket to notice. A connection on a non-default network gets no
  such event when that network later disappears; the ping timeout catches it.
- An attempt in flight is abandoned on any change of the default network. Closing a transport
  aborts its connect, so nothing runs out to a timeout.
- A connection whose network merely stopped being the default is left alone. With a LAN server on a
  WiFi that lost its uplink, it is the only connection that reaches the server.
- The first attempt after a change is immediate, unless the last dial was under a second ago, so
  a flapping network cannot cause a connect storm.

## TCP: the control connection

Each connection has one TLS socket, the control connection. It carries every control message,
and our voice too until UDP has proven itself.

### TLS transport

`net/MumbleTcpTransport`. An instance connects once; a reconnect is a new instance, so no teardown
state leaks between connections. One reader coroutine delivers frames, and its `finally` is the only
caller of `onClosed`: exactly once, never from inside `onFrame`, so listeners need no locking. The
send queue is small on purpose (32 frames): a larger buffer would only hide a stalled socket for
longer. Voice sent over TCP goes through the same queue, and a full queue refuses the packet.

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

### Connection state machine

`protocol/ConnectionStateMachine`. It sends `Version` and `Authenticate`, then waits for `ServerSync`
under its own 15 s deadline, since the transport's own timeouts (10 s each for the socket connect
and the TLS handshake) end there. State transitions settle by compare-and-set, and the first failure
wins.

- Pings run on the boot clock, which counts time in doze.
- The published value is the instant of the last server reply, not its age. An age is stale as
  soon as it is published, and a dozing device fires no tick to refresh it.
- Voice arriving over TCP is delivered by callback, not through a flow. A `StateFlow` conflates, and a
  dropped emission would be dropped audio.

## UDP: the low-latency voice path

Voice can travel two ways:

- **Over TCP:** inside the TLS control connection, wrapped in `UDPTunnel` frames. Always available.
  The code calls this *tunneled*.
- **Over UDP:** as encrypted datagrams on a socket beside the control connection. This is the
  low-latency path: a lost datagram delays nothing behind it, where TCP holds every later packet
  back until the lost one is resent.

The two directions are decided separately, by different parties:

| Direction | Who decides | Rule |
|---|---|---|
| **Outgoing** voice, what we send (*uplink*) | this app (`VoicePath`) | TCP until a UDP ping has been answered. Back to TCP when UDP pings stop being answered. |
| **Incoming** voice, what we hear (*downlink*) | the server | UDP once it knows our UDP address, which our first UDP ping gives it. TCP after it receives any `UDPTunnel` frame from us, voice or ping. UDP again after we send it audio over UDP. |

So the server's choice for incoming voice follows the last thing we actually sent it. This app
accepts incoming voice from both at all times, so it never needs to know which one the server
picked.

### Outgoing voice: promote and demote

`net/VoicePath` holds one fact per connection: is outgoing voice on UDP? It starts as no.
`Connection.sendVoice` reads it for every packet. The code has a name for each change:

- **Promote:** outgoing voice switches from TCP to UDP, starting with the next packet.
- **Demote:** outgoing voice goes back to TCP, starting with the next packet, and the count of
  answered pings starts again from zero.

Neither touches this app's sockets. The UDP socket stays open, a UDP ping still goes out every
5 s, and incoming voice is still accepted from both. The status line shows the current one, as `UDP`
or `TCP` beside the round trip.

| Change | Happens when |
|---|---|
| Promote | Two UDP pings have been answered since the last demotion. A new connection is credited with one, so if its pings are answered from the start, the first answer is enough. Needing two after a demotion keeps a path that works on and off from flipping back and forth. |
| Demote | Two UDP pings in a row go unanswered, or the socket refuses a voice datagram. A refused datagram is sent over TCP instead, in the same call. A demotion can happen while voice is still on TCP; then it only resets the count. |

For example:

```
connection opens        outgoing voice on TCP; first UDP ping sent
ping answered           promote: outgoing voice on UDP
two pings unanswered    demote:  outgoing voice on TCP
ping answered           one of the two answers now needed
ping answered           promote: outgoing voice on UDP
```

The ping is the only evidence used:

- An answered ping proves both directions at once.
- The server answers a UDP ping over UDP even while our voice is on TCP, so pings keep testing the
  UDP path after a demotion. That is what lets voice return to UDP.
- The cipher's counters are never consulted: our encrypt counter advances whether or not a
  datagram arrives.
- Every accepted answer also feeds the average our TCP ping reports, which is what other clients
  show about us ([mumble-protocol.md](mumble-protocol.md), Ping).

### When UDP stops working

A demotion fixes outgoing voice. Incoming voice needs one more step, because of two rules the
server follows:

- It learns our UDP address once and never updates it.
- It keeps sending incoming voice to that address until it receives a `UDPTunnel` frame from us,
  voice or ping.

Someone who is only listening sends none. So if UDP dies while we listen, for example because a
NAT gave us a new port, the server would go on sending to a dead address. We would hear nothing,
while the control connection looked healthy. The same happens on a link where our pings reach
the server but its answers never reach us: it has our address, and we cannot hear on it.

So whenever two UDP pings in a row go unanswered, whether or not outgoing voice was on UDP,
this app also sends one ping over TCP (`TUNNEL_PING`). The server never answers it. Its
arrival is enough: the server switches our incoming voice to TCP on any `UDPTunnel` frame,
before decoding it, so no other user hears anything. When the cause was a refused voice
datagram, that datagram went over TCP and had the same effect.

What follows from that:

- **Detection takes two to three ping intervals.** The transport judges each ping when it sends
  the next one, and reports two in a row unanswered once per outage.
- **Incoming voice returns to UDP only when we next send audio over UDP,** which needs a
  promotion and then us talking. A listener who never talks keeps hearing over TCP for the rest
  of the connection.
- **A changed port never recovers.** The server ignores datagrams from a port it did not learn,
  so the pings stay unanswered and voice stays on TCP both ways until the connection is replaced.
- **A brief loss** costs two unanswered pings to demote and two answers to promote again.
- **A replacement connection starts over:** new address, new cipher, outgoing voice on TCP.

Pinned against a real server by `LiveServerIntegrationTest`.

### UDP voice

The socket itself: `net/MumbleUdpTransport`. A connected `DatagramChannel` aimed at the control
connection's remote address, `CryptState` encrypting and decrypting every datagram, and one thread
blocked in read.

- It is opened as soon as the control socket is up, and closed with the connection.
- If it cannot be opened, nothing is lost. The server never learns a UDP address and sends our
  incoming voice over TCP.
- The state machine owns the cipher, because `CryptSetup` is a control message. It sends the first
  UDP ping when the cipher is keyed, and one more with every TCP ping.
- It listens from the start. The server sends incoming voice over UDP from our first ping, whether
  or not we have ever sent audio, so a socket that pinged without listening would hear nothing
  ([mumble-protocol.md](mumble-protocol.md), Voice framing).

## Tests

`AppStateImplTest`, `MumbleTcpTransportTest`, `MumbleUdpTransportTest`, `VoicePathTest`,
`MumbleTrustManagerTest`, `ConnectionStateMachineTest`, and `LiveServerIntegrationTest` against a
real server.
