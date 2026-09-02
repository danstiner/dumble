# The Mumble protocol

The wire protocol as Dumble speaks it: Mumble 1.5+, protobuf voice, Opus only. This is the
reference for what crosses the network; how Dumble is structured around it lives in
`docs/architecture.md` and `docs/connection.md`. Upstream's own documentation is at
[mumble.readthedocs.io](https://mumble.readthedocs.io/) and in the protobuf
schemas vendored under `app/src/main/proto/` (`Mumble.proto`, `MumbleUDP.proto`), whose comments
are normative.

## Two channels

A session is one TLS-over-TCP **control channel** plus, optionally, one encrypted UDP **voice
channel** to the same host and port. Everything the UDP channel carries can also ride the control
channel inside `UDPTunnel` frames, so TCP alone is a complete session — that is Dumble today.
The UDP transport is in flight (`docs/superpowers/specs/2026-08-27-udp-voice-transport-design.md`);
its crypto layer is `net/Ocb2.kt` and `net/CryptState.kt`.

## Control channel

TLS to the server's port (default 64738). Most servers are self-signed; trust is
pin-on-first-use (`docs/connection.md`). Framing is fixed:

```
[u16 message type][u32 payload length][payload]     big-endian
```

Payloads are the protobuf messages of `Mumble.proto`, except type 1 (`UDPTunnel`), whose payload
is a raw voice datagram, not protobuf. Types Dumble does not model are ignored, which is also the
protocol's forward-compatibility story. Dumble caps inbound payloads at 8 MiB
(`MumbleCodec.MAX_TCP_PAYLOAD`) as a hostile-length guard; the protocol itself has no cap.

| id | message | notes |
|---:|---|---|
| 0 | Version | both directions, first message each way |
| 1 | UDPTunnel | voice datagram over TCP; **not** protobuf |
| 2 | Authenticate | username, optional password, tokens, `opus=true` |
| 3 | Ping | client-initiated, server echoes; carries crypt stats |
| 4 | Reject | terminal handshake failure (bad auth, wrong version…) |
| 5 | ServerSync | handshake complete; your session id, welcome text |
| 6–9 | ChannelRemove/State, UserRemove/State | the state stream (below) |
| 11 | TextMessage | chat |
| 12 | PermissionDenied | rejected action, with reason enum |
| 15 | CryptSetup | OCB2 key material and resync (below) |
| 19 | VoiceTarget | registers whisper/shout targets 1–30 (not used) |
| 21 | CodecVersion | legacy CELT negotiation; ignored, Opus is assumed |
| 24 | ServerConfig | limits (message length, image length…) |

The rest (BanList, ACL, QueryUsers, UserList, ContextAction…, RequestBlob, SuggestConfig,
UserStats, PluginDataTransmission) are administrative or optional; Dumble ignores what it does
not use.

**Version encoding.** Two encodings coexist. Legacy `version_v1` is a u32:
`major<<16 | minor<<8 | patch` — minor and patch saturate at 255, which Mumble 1.3/1.4/1.5 point
releases actually hit, hence `version_v2`: a u64 with 16 bits each for major, minor, patch at
bits 48/32/16 (low 16 reserved). Dumble sends both, prefers v2 when reading, and advertises
itself as 1.5.0 (`SessionStateMachine.CLIENT_*`).

**Handshake.** After TLS, both sides send `Version`; the client follows immediately with
`Authenticate` without waiting for the server's. The server then streams `CryptSetup`,
`CodecVersion`, every `ChannelState`, every `UserState`, and finally `ServerSync`, which is the
"you are in" — until it arrives nothing is authoritative. Failure is a `Reject` or a closed
socket. The protocol has no handshake timeout; Dumble imposes 15 s to `ServerSync`
(`HANDSHAKE_DEADLINE_MS`). Servers older than 1.5 are refused at the `Version` message: a 1.4
server parses protobuf voice as malformed legacy CELT and silently drops every frame, and voice
is the point of connecting.

**State stream.** After sync the server pushes deltas: `ChannelState`/`ChannelRemove`,
`UserState`/`UserRemove` (a `UserState` is a sparse diff — only changed fields are set, keyed by
`session`), `TextMessage`. There is no polling; the connection is the subscription.

**Ping.** The client must ping or be dropped: servers time out silent connections (murmur's
default allows ~30 s). Dumble pings every 5 s (`PING_INTERVAL_MS`) and marks the session
degraded after 15 s without a reply (two unanswered pings) — display state only; only a dead
socket or the server ends a session. The `Ping` message carries the sender's UDP crypt
statistics (`good`, `late`, `lost`, `resync`); the server's reply carries its own reception
numbers, which is how you learn whether the server can decrypt *your* UDP packets. The desktop
client uses them to decide UDP health.

## Voice framing

Since 1.5, a voice datagram is one header byte then a protobuf message from `MumbleUDP.proto`:

```
0x00 → Audio    0x01 → Ping
```

(The pre-1.5 bit-packed framing — type and target in one byte, varint-coded session and
sequence — is deliberately not implemented; refusing pre-1.5 servers removes it whole.)

`Audio` uplink sets `target` (0 = normal talk, 1–30 = a registered `VoiceTarget`, 31 = server
loopback), `frame_number`, `opus_data`, and `is_terminator` on the last packet of a
transmission. Downlink replaces `target` with `context` (normal / shout / whisper / listener)
and adds `sender_session`; `frame_number` orders packets within one speaker's stream and is what
playout's jitter logic keys on. One packet carries one 20 ms mono Opus frame end to end
(`docs/capture.md`). `positional_data` is a non-goal (`CLAUDE.md`); `volume_adjustment` is
a server-determined per-listener volume adjustment, currently ignored.

`Ping` carries an opaque client timestamp the server echoes untouched — liveness proof for the
UDP path specifically, since it round-trips the same encryption as audio.

**Over TCP** the same `[header byte][protobuf]` bytes travel as a `UDPTunnel` frame, unencrypted
beyond TLS. Tunneled voice inherits TCP's ordering and its head-of-line blocking, which is why
the UDP path exists.

## The UDP channel: OCB2-AES128

UDP datagrams are encrypted with OCB2 under AES-128. The cipher is `net/Ocb2.kt` and the packet
layer around it is `net/CryptState.kt`; both are written from Rogaway's OCB2 paper and this
protocol description rather than ported from upstream's `CryptStateOCB2.cpp`, so what follows
describes the wire format both must produce, then where Dumble's receiver differs by choice.
The handshake `CryptSetup` delivers the shared key and two 16-byte
nonces — `client_nonce` seeds the client's encrypt IV and the server's decrypt IV,
`server_nonce` the reverse. On the wire:

```
[IV low byte][tag byte 0][tag byte 1][tag byte 2][OCB2 ciphertext]
```

4 bytes of overhead; ciphertext length equals plaintext length (OCB2 is length-preserving; a
4-byte datagram with no payload is legal). The tag is the first 3 bytes of the 16-byte OCB
authentication tag.

**IV scheme.** The IV is the full 16-byte nonce used as a counter: the sender increments it
before every packet, carrying into higher bytes on wrap. Only the low byte is transmitted, so the
receiver must rebuild the other fifteen from the counter it already holds — and that byte gives
it 256 candidates to choose between. Which one it picks is receiver-local: nothing on the wire
records the choice, so a client is free to set its own tolerance for reordering and loss.

Dumble splits the 256 values 63 behind the highest counter accepted and the rest ahead, so a
burst of up to 191 consecutive losses is ridden out and 63 packets of reordering are tolerated,
with one guess covering both — never a second decrypt attempt an attacker could force. The
asymmetry is deliberate: 63 behind is already 1.26 s of reordering at 50 packets/s, an order of
magnitude past what networks produce, while multi-second loss bursts — a WiFi roam, a cell
handoff — are routine, and only the behind side costs replay-tracking state. Anything
further out rebuilds to the wrong counter and fails the tag, which is the right answer: at that
distance a stale packet and a forged one are indistinguishable. Upstream chooses differently,
tolerating 29 behind and jumping forward to the 128-value ambiguity boundary of the mod-256
counter.

Replays are caught before any decryption. Dumble keeps the sliding-window bitmap IPsec uses
(RFC 6479) and SRTP requires at least 64 of (RFC 3711 §3.3.2) — the highest counter accepted plus
one bit for each of the 63 below it — sized by the one 64-bit word that holds it, not by the wire:
the hint only caps the late-plus-ahead split at 256.
Upstream instead keeps a 256-slot history table, one slot per value of `iv[0]`, recording the
"generation" (`iv[1]`) last accepted there.

**Upstream's replay table has a stall defect the bitmap cannot have.** Upstream zero-fills the
byte-valued history table, so "never visited" is indistinguishable from "visited in generation
0x00". Nonces are random, so 1 keying in 256 starts with `iv[1] == 0` — and then the first lost
or reordered packet lands on an untouched slot, falsely matches as a replay, and is rejected;
since rejection restores the IV, every following packet recomputes the identical comparison and
the receive direction wedges permanently. Upstream never noticed because the desktop client masks
it — any failed decrypt with no success in 5 s triggers a crypt resync (below) and the symptom
collapses to a rare few-second one-way dropout indistinguishable from network loss.

A bitmap has no sentinel to confuse with a real value: a bit is set or it is not, and the seed is
marked consumed at keying so the first packet under a new key is unambiguously seed + 1. The
defect is structurally absent rather than patched. Either way this is receiver-local bookkeeping —
no wire byte derives from it — so it is a divergence from upstream's code, not its protocol.

**Resync.** `CryptSetup` doubles as the recovery channel: a client that decrypts nothing for 5 s
sends an empty `CryptSetup`, and the server replies with its current encrypt IV as
`server_nonce`, re-seeding the client's decrypt state. The server recovers its own decrypt
direction symmetrically by requesting the client's nonce. Rate-limited to one request per 5 s.
Voice lost during the gap is simply gone — the stream is live audio, not a transcript.

Dumble adopts the reply unconditionally, as upstream does. Safe because of when a request can
fire: only after five seconds without a good decrypt, so the top has stopped moving and an honest
reply lands at or ahead of it — in practice every resync is a forward adoption. (The one honest
exception: a junk datagram can trip a request just as a quiet stream resumes, and adopting the
in-flight reply then reopens at most one window of just-resumed audio — the same trade upstream
makes on every resync.) A top somehow ahead of the
server (a forged tag winning a 2^24 collision moves it up to 192 ahead) is exactly what adoption
heals; refusing to rewind would let one poisoned top refuse every truthful correction. The
restart consumes rather than clears, so the window below the adopted counter still cannot
replay — a guarantee upstream's generation table does not give.

**OCB2 is cryptographically broken** (Inoue et al. 2019, [eprint 2019/311](https://eprint.iacr.org/2019/311)):
universal forgery and full plaintext recovery when the attacker controls plaintexts with
near-zero blocks. It remains on the wire for compatibility — every 1.5 server speaks it and
nothing else for UDP. The two countermeasures peers expect are therefore implemented from the
paper: when the last full plaintext block is all zeros but the last byte (the attack's
precondition), the encryptor flips one plaintext bit before encrypting (upstream: “modify the
packet in a way which should not affect the audio”), and the decryptor refuses a datagram whose
recovered final block matches the current whitening mask — the shape the forgery's tail decrypts
to — even when the tag matches. The TCP tunnel does not use OCB2 at all; tunneled voice has
TLS's integrity instead.

## Divergences from the desktop client

Deliberate, documented where they live:

- **Pre-1.5 servers refused** rather than joined without voice (`SessionStateMachine`).
- **Legacy voice framing and CELT** not implemented — subsumed by the above.
- **Positional audio** not implemented; voice is mono end to end (`CLAUDE.md` non-goals).
- **Sliding-window replay bitmap** rather than a generation table, avoiding the stall above, and
  a wider reordering window (63 late, 191 consecutive losses) (`net/CryptState.kt`).
- **Pin-before-authority trust ordering** (`docs/connection.md`).
- **Client-side handshake deadline** — the protocol has none, Dumble enforces 15 s.
