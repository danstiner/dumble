# The Mumble protocol

The wire protocol as this client speaks it: Mumble 1.5 and newer, protobuf voice, Opus only. This
page is a reference for what crosses the network, plus the reasoning wherever this client had a
choice. The choices are collected in the [last
section](#where-this-client-departs-from-the-desktop-client). How the code is organised around the
protocol is in [network.md](network.md).

Upstream documents the protocol at [mumble.readthedocs.io](https://mumble.readthedocs.io/). The
protobuf schemas are vendored in `app/src/main/proto/` (`Mumble.proto`, `MumbleUDP.proto`), and
their comments are normative.

## Two channels

A session is one TLS-over-TCP **control channel** and, optionally, one encrypted UDP **voice
channel** to the same host and port. Anything UDP carries can also travel on the control channel
inside `UDPTunnel` frames, so TCP alone is a complete session.

This client opens the UDP channel on every session, receives on it from the first ping, and sends
voice on it once a ping has round-tripped ([network.md](network.md)).

## Control channel

TLS to the server's port, 64738 by default. Every message is framed the same way:

```
[u16 message type][u32 payload length][payload]     big-endian
```

The payload is a protobuf message from `Mumble.proto`, except type 1 (`UDPTunnel`), which is a raw
voice datagram. Message types this client does not model are ignored, which is also how the protocol
stays forward compatible. This client refuses inbound payloads over 8 MiB
(`MumbleCodec.MAX_TCP_PAYLOAD`) to guard against a hostile length; the protocol has no limit.

| id | message | notes |
|---:|---|---|
| 0 | Version | both directions, first message each way |
| 1 | UDPTunnel | voice datagram over TCP; not protobuf |
| 2 | Authenticate | username, optional password, tokens, `opus=true` |
| 3 | Ping | sent by the client, echoed by the server; carries statistics |
| 4 | Reject | the handshake failed (bad password, name in use…) |
| 5 | ServerSync | handshake complete; our session id and the welcome text |
| 6–9 | ChannelRemove/State, UserRemove/State | the state stream |
| 11 | TextMessage | chat |
| 12 | PermissionDenied | an action was refused, with a reason |
| 15 | CryptSetup | the UDP cipher's key and nonces, and resync |
| 19 | VoiceTarget | registers whisper and shout targets 1–30; unused |
| 21 | CodecVersion | legacy CELT negotiation; ignored, Opus is assumed |
| 24 | ServerConfig | limits such as message and image length |

The rest (BanList, ACL, QueryUsers, UserList, ContextAction, RequestBlob, SuggestConfig, UserStats,
PluginDataTransmission) are administrative or optional. This client uses `UserStats` and ignores the
others.

### Version encoding

Two encodings coexist. Legacy `version_v1` is a u32, `major<<16 | minor<<8 | patch`. Minor and patch
saturate at 255, which real 1.3 to 1.5 point releases reach. `version_v2` is a u64 with 16 bits each
for major, minor and patch at bits 48, 32 and 16; the low 16 are reserved. This client sends both,
prefers v2 when reading, and announces itself as 1.5.0 (`SessionStateMachine.CLIENT_*`).

### Handshake

1. After TLS, both sides send `Version`. The client sends `Authenticate` straight after, without
   waiting for the server's.
2. The server streams `CryptSetup`, `CodecVersion`, every `ChannelState` and every `UserState`.
3. `ServerSync` ends the handshake. Nothing received before it is authoritative.

Failure is a `Reject` or a closed socket. The protocol has no handshake timeout; this client allows
15 s to `ServerSync` (`HANDSHAKE_DEADLINE_MS`).

This client refuses servers older than 1.5 at the `Version` message. A 1.4 server parses protobuf
voice as malformed legacy CELT and silently drops every frame, so joining one would mean a session
with no voice.

The client certificate is not a protocol message. It is part of the TLS handshake, and
`Authenticate` never mentions it ([network.md](network.md), Client certificate).

### State stream

After `ServerSync` the server pushes changes: `ChannelState`, `ChannelRemove`, `UserState`,
`UserRemove`, `TextMessage`. A `UserState` is a sparse diff keyed by `session`, with only the
changed fields set. Nothing is polled.

### Ping

The client must ping or be dropped: murmur's default drops a silent connection after about 30 s.
This client pings every 5 s (`PING_INTERVAL_MS`). A link with no reply for three intervals is ended
as a timeout and replaced ([network.md](network.md), Reconnect).

The `Ping` message also carries statistics, which the server stores and returns to other clients
in `UserStats`:

- **UDP cipher counts** (`good`, `late`, `lost`, `resync`). The server's reply carries its own,
  which say whether it can decrypt our datagrams. Desktop Mumble judges UDP health from them.
  We send them too, but judge by the UDP ping alone ([network.md](network.md), Outgoing voice:
  promote and demote).
- **Round trips**: mean and variance over the last twenty replies for each of TCP and UDP
  (`tcp_ping_avg`, `udp_ping_avg`, `*_ping_var`), and reply counts (`tcp_packets`, `udp_packets`,
  which count ping replies and not voice, on desktop too). This is how another client shows our
  round trip. A UDP average being present means a UDP ping has round-tripped on this link; it
  stays set after our voice falls back to TCP (`PingAverage`).

### Self mute and deafen

A client sets both in its own `UserState`. The server stores and echoes them and adds one coupling
(`Server::msgUserState`): `self_deaf=true` forces `self_mute=true`, and `self_mute=false` forces
`self_deaf=false`. The server drops voice on `self_mute` (and `mute`, `suppress`), never on
`self_deaf`. It never lifts a mute that a deafen forced, so what an undeafen does to the mute is
up to the client.

This client follows desktop Mumble (`on_qaAudioMute_triggered` and `on_qaAudioDeaf_triggered` in
`src/mumble/MainWindow.cpp`):

| Ask | Result |
|---|---|
| Deafen | Deafened and muted. |
| Undeafen | The mute returns to what it was before the deafen. A mute the user set stays, so a muted microphone does not reopen unasked. A mute the deafen forced lifts. |
| Unmute while deafened | Unmuted and undeafened, whoever set the mute. |
| Mute while deafened | No control can ask for it: the button already reads muted. |

Desktop keeps three flags (`bMute`, `bDeaf`, `unmuteOnUndeaf`). `DeafenState` keeps the two asks,
the deafen and the user's own mute, and derives the wire's `self_mute` as either one. That is the
same machine with the invalid states unrepresentable, and every ask is an idempotent set. It was
checked against a port of the two desktop handlers over every toggle sequence to depth 16 and every
explicit on/off sequence to depth 8. The one difference is a mute asked for while deafened:
`DeafenState` keeps it as the user's own, where desktop's shortcut ignores it. This client sends
both fields with every ask, so the server's coupling never has to act.

**The ask and the echo are separate states.** Only a user's own `UserState` can write their
`self_mute` and `self_deaf` (murmur returns early on anyone else's, and its Ice interface only
reads them), so the echo can only lag the ask, and it says nothing during a
reconnect. The Mute and Deafen controls, the Talk button and the capture gate read the ask. The
roster row reads the echo, which is what everyone else sees.

## Voice framing

Since 1.5 a voice datagram is one type byte and then a protobuf message from `MumbleUDP.proto`:

```
0x00 → Audio    0x01 → Ping
```

The older bit-packed framing is not implemented; refusing servers before 1.5 removes the need.

**Audio, uplink.** `target` (0 is normal talk, 1–30 a registered `VoiceTarget`, 31 server
loopback), `frame_number`, `opus_data`, and `is_terminator` on the last packet of a transmission.

**Audio, downlink.** `context` (normal, shout, whisper, listener) replaces `target`, and
`sender_session` is added. `frame_number` orders the packets of one speaker and is what the
jitter buffer keys on. Each packet this client sends is one 20 ms mono Opus frame.

This client ignores `positional_data` (a non-goal) and `volume_adjustment`, the server's
per-listener volume change.

**Over TCP** the same bytes travel as a `UDPTunnel` frame with no encryption beyond TLS. Tunneled
voice inherits TCP's ordering and its head-of-line blocking, which is why the UDP path exists.

### The UDP ping, and what the server does with it

`Ping` carries a client timestamp that the server echoes unchanged. Because it goes through the
same encryption as audio, a reply proves the UDP path specifically.

How murmur treats a client's UDP address shapes everything we do here
(`src/murmur/Server.cpp`, checked at `90c2184c8`):

1. **It learns the address only from a datagram it can decrypt,** by trying the keys of every
   session from that host.
2. **It learns it once.** Registration takes the session out of the set that unknown datagrams
   are matched against, so a datagram from a new port is dropped and the downlink keeps going to
   the old one.
3. **It sends the downlink over UDP while a per-user flag is set.** The flag starts set. Any
   tunneled frame from that client that passes the length check clears it, before the frame is
   decoded. A UDP audio packet sets it again.
4. **It answers a ping that carries only a timestamp over UDP,** whatever the flag says. It does
   not answer a tunneled ping.

What follows:

- The first ping, sent the moment `CryptSetup` keys the cipher and then every 5 s, registers the
  address, keeps the NAT mapping alive and proves both directions.
- From that first ping a client receives over UDP even if it has never transmitted. So the socket
  must be receiving before anything is sent on it.
- A tunneled ping is how a listener asks for its downlink back over TCP
  ([network.md](network.md), When UDP stops working).

## The UDP channel: OCB2-AES128

UDP datagrams are encrypted with OCB2 under AES-128. `net/Ocb2.kt` is the cipher and
`net/CryptState.kt` the packet layer. Both were written from Rogaway's OCB2 paper and this
description of the wire format, not ported from upstream's `CryptStateOCB2.cpp`.

`CryptSetup` delivers the key and two 16-byte nonces. `client_nonce` seeds the client's encrypt IV
and the server's decrypt IV; `server_nonce` the reverse. A datagram is:

```
[IV low byte][tag byte 0][tag byte 1][tag byte 2][OCB2 ciphertext]
```

Four bytes of overhead. The ciphertext is as long as the plaintext, and a datagram with no payload
is legal. The tag is the first 3 bytes of the 16-byte OCB tag.

### The IV

The IV is the whole 16-byte nonce used as a counter. The sender increments it before every packet,
carrying into the higher bytes. Only the low byte is sent, so the receiver rebuilds the other
fifteen from the counter it holds and must choose among 256 candidates. That choice is local to
the receiver: nothing on the wire records it.

This client reads the byte as the counter nearest the highest one accepted: the difference of the
two low bytes as a signed byte, 128 behind to 127 ahead. One guess covers both directions, so an
attacker can never force a second decrypt.

- **Up to 127 ahead** is accepted, so 126 packets lost in a row are ridden out: 2.5 s of one
  talker at 50 packets a second.
- **Up to 32 behind** is accepted if the replay window has not seen it. That is 0.64 s of
  reordering for one talker, far more than networks produce.
- **Further behind** is refused without decrypting. A sender more than 127 ahead reads as one of
  these, or rebuilds to the wrong counter and fails the tag; either way it takes a resync.

Upstream accepts 29 behind and 127 ahead, plus 128 ahead when the byte has not wrapped.

### Replay protection

Replays are rejected before any decryption. `ReplayWindow` keeps the sliding-window bitmap that
IPsec uses (RFC 4303 §3.4.3): the highest counter accepted, plus one bit for each of the 32 below
it, in one 64-bit word.

Upstream keeps a 256-slot table, one slot per value of `iv[0]`, holding the `iv[1]` last accepted
there. The table is zero-filled, so "never visited" looks like "visited when `iv[1]` was 0".
Nonces are random, so one keying in 256 starts with `iv[1] == 0`. Then the first lost or reordered
packet lands on an untouched slot and is rejected as a replay. The rejection restores the IV, so
every later packet repeats the comparison and the receive direction is stuck. The desktop client
hides this: 5 s without a good decrypt triggers a resync, so the symptom is a rare one-way dropout
that looks like network loss.

A bitmap has no sentinel value to confuse with a real one, and this client marks the seed consumed
at keying, so the first packet under a new key is unambiguously seed + 1. All of this is local
bookkeeping; no byte on the wire depends on it.

### Resync

`CryptSetup` is also the recovery channel.

- A client that decrypts nothing for 5 s sends an empty `CryptSetup`. The server replies with its
  current encrypt IV as `server_nonce`, which re-seeds the client's decrypt state.
- The server recovers the same way: an empty `CryptSetup` from it is answered with our encrypt IV
  as `client_nonce`.
- Each end sends at most one request per 5 s.
- A malformed `CryptSetup` is logged and dropped, as upstream does, and does not end the session.
- Voice lost in the gap stays lost.

This client adopts the server's reply unconditionally, as upstream does. A request fires only after
5 s without a good decrypt, so the highest counter has stopped moving and an honest reply lands at
or ahead of it. Two edge cases:

- A junk datagram can trigger a request just as a quiet stream resumes. Adopting the reply then
  reopens at most one window of just-resumed audio to replay, the same trade upstream makes.
- A forged tag that wins a 2^24 collision can move the highest counter up to 127 ahead of the
  server. Adoption is what repairs that; refusing to move backwards would let one bad value
  reject every honest correction.

The restart marks the adopted counter consumed instead of clearing the window, so packets below it
still cannot be replayed. Upstream's table does not guarantee that.

### OCB2 is broken

Inoue et al. 2019 ([eprint 2019/311](https://eprint.iacr.org/2019/311)) showed universal forgery and
full plaintext recovery against OCB2 when the attacker controls plaintexts with near-zero blocks.
Every 1.5 server speaks OCB2 and nothing else on UDP, so it stays. This client implements the two
countermeasures peers expect, from the paper:

- **Encrypt.** When the last full plaintext block is all zeros except its last byte, which is the
  attack's precondition, one plaintext bit is flipped before encrypting.
- **Decrypt.** A datagram whose recovered final block equals the current whitening mask, the
  shape a forgery's tail decrypts to, is refused even when its tag matches.

The TCP tunnel does not use OCB2; tunneled voice has TLS's integrity.

## Where this client departs from the desktop client

This client matches desktop behaviour by default. Each departure is deliberate.

| This client | Why | Cost | Where |
|---|---|---|---|
| Refuses servers older than 1.5 | A 1.4 server silently drops protobuf voice. Refusing also removes the legacy voice framing and CELT. | Old servers cannot be joined, even for chat. | `SessionStateMachine` |
| No positional audio | A non-goal; voice is mono end to end. | Positions sent by others are ignored. | `AGENTS.md` |
| Replay bitmap instead of a generation table | The table can wedge the receive direction (Replay protection). | None on the wire. | `net/ReplayWindow.kt` |
| IV window: 32 behind, 127 ahead | Close to upstream's 29 and 127, as a signed byte and one comparison. | None on the wire; the choice is local to the receiver. | `net/ReplayWindow.kt` |
| Pin checked before the certificate authorities | An authority must not override the user's chosen server. | A renewed certificate must be accepted again. | [network.md](network.md), Trust |
| 15 s handshake deadline | The protocol has none, and a silent server would hang the connect. | A server slower than that is reported as a timeout. | `SessionStateMachine` |
| UDP health judged by the ping, not the cipher counts | Our encrypt counter advances whether or not a datagram arrives. | Voice can stay on a lossy UDP path: it takes two unanswered pings in a row to move it back to TCP. | `net/VoicePath.kt` |
| A mute asked for while deafened is kept | It follows from storing the two asks separately. | None: no control can ask for it. | `DeafenState` |
