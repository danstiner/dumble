# The Mumble protocol

The wire protocol as dumble speaks it: Mumble 1.5+, protobuf voice, Opus only. This is the
reference for what crosses the network; how dumble is structured around it lives in
`docs/architecture.md` and `docs/connection.md`. Upstream's own documentation is at
[mumble.readthedocs.io](https://mumble.readthedocs.io/) and in the protobuf
schemas vendored under `app/src/main/proto/` (`Mumble.proto`, `MumbleUDP.proto`), whose comments
are normative.

## Two channels

A session is one TLS-over-TCP **control channel** plus, optionally, one encrypted UDP **voice
channel** to the same host and port. Everything the UDP channel carries can also ride the control
channel inside `UDPTunnel` frames, so TCP alone is a complete session — that is dumble today.
The UDP transport is in flight (`docs/superpowers/specs/2026-08-27-udp-voice-transport-design.md`);
its crypto layer is `net/CryptState.kt` (#116, in flight).

## Control channel

TLS to the server's port (default 64738). Most servers are self-signed; trust is
pin-on-first-use (`docs/connection.md`). Framing is fixed:

```
[u16 message type][u32 payload length][payload]     big-endian
```

Payloads are the protobuf messages of `Mumble.proto`, except type 1 (`UDPTunnel`), whose payload
is a raw voice datagram, not protobuf. Types dumble does not model are ignored, which is also the
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
UserStats, PluginDataTransmission) are administrative or optional; dumble ignores what it does
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
socket. The protocol has no handshake timeout; dumble imposes 15 s to `ServerSync`
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

UDP datagrams are encrypted with OCB2 under AES-128 (`net/CryptState.kt`, a port of upstream
`CryptStateOCB2.cpp`). The handshake `CryptSetup` delivers the shared key and two 16-byte
nonces — `client_nonce` seeds the client's encrypt IV and the server's decrypt IV,
`server_nonce` the reverse. On the wire:

```
[IV low byte][tag byte 0][tag byte 1][tag byte 2][OCB2 ciphertext]
```

4 bytes of overhead; ciphertext length equals plaintext length (OCB2 is length-preserving; a
4-byte datagram with no payload is legal). The tag is the first 3 bytes of the 16-byte OCB
authentication tag.

**IV scheme.** The IV is the full 16-byte nonce used as a counter: the sender increments byte 0
before every packet, carrying into higher bytes on wrap. Only byte 0 is transmitted; the
receiver reconstructs the rest by tracking. In-order is `(iv[0]+1) & 0xFF`. The window is
asymmetric: packets up to 29 behind are decrypted against a temporarily rewound IV, wrap-aware
(byte 1 borrows); 30 or more behind are dropped. A forward gap skips the IV ahead, wrap-aware
(byte 1 carries), and counts the skipped packets as `lost` — accepted all the way to the
128-value ambiguity boundary of the mod-256 counter, past which a jump reads as behind and is
dropped. Replays are caught by a 256-slot history table, one slot per value of `iv[0]`, recording the "generation" (`iv[1]`) last accepted there.

**Upstream's replay table has a stall defect, fixed here.** Upstream zero-fills the byte-valued
history table, so "never visited" is indistinguishable from "visited in generation 0x00".
Nonces are random, so 1 keying in 256 starts with `iv[1] == 0` — and then the first lost or
reordered packet lands on an untouched slot, falsely matches as a replay, and is rejected; since
rejection restores the IV, every following packet recomputes the identical comparison and the
receive direction wedges permanently. Measured on this port with upstream semantics: after one
dropped packet, 0 of the next 600 decrypt (the stall survives IV wrap); with generation 1
instead of 0, 600 of 600. Upstream never noticed because the desktop client masks it — any
failed decrypt with no success in 5 s triggers a crypt resync (below) and the symptom collapses
to a rare few-second one-way dropout indistinguishable from network loss. Dumble instead fixes
the table: entries widened to `Int`, unvisited slots `-1`, a generation no byte can hold. The
table is receiver-local bookkeeping — no wire byte derives from it — so this diverges from
upstream's code, not its protocol. Pinned by `CryptStateTest.lossDoesNotStallWhenGenerationByteIsZero`.

**Resync.** `CryptSetup` doubles as the recovery channel: a client that decrypts nothing for 5 s
sends an empty `CryptSetup`, and the server replies with its current encrypt IV as
`server_nonce`, re-seeding the client's decrypt state. The server recovers its own decrypt
direction symmetrically by requesting the client's nonce. Rate-limited to one request per 5 s.
Voice lost during the gap is simply gone — the stream is live audio, not a transcript.

**OCB2 is cryptographically broken** (Inoue et al. 2019, [eprint 2019/311](https://eprint.iacr.org/2019/311)):
universal forgery and full plaintext recovery when the attacker controls plaintexts with
near-zero blocks. It remains on the wire for compatibility — every 1.5 server speaks it and
nothing else for UDP. Upstream's counter-cryptanalysis is ported as-is: when the second-to-last
plaintext block is all zeros but the last byte (the attack's precondition), the encryptor flips
one plaintext bit before encrypting (upstream: “modify the packet in a way which should not
affect the audio”), and the decryptor treats the pattern as a detected forgery. The TCP tunnel does not use OCB2 at all;
tunneled voice has TLS's integrity instead.

## Divergences from the desktop client

Deliberate, documented where they live:

- **Pre-1.5 servers refused** rather than joined without voice (`SessionStateMachine`).
- **Legacy voice framing and CELT** not implemented — subsumed by the above.
- **Positional audio** not implemented; voice is mono end to end (`CLAUDE.md` non-goals).
- **Replay history sentinel** widened to fix the stall above (`net/CryptState.kt`).
- **Pin-before-authority trust ordering** (`docs/connection.md`).
- **Client-side handshake deadline** — the protocol has none, dumble enforces 15 s.
