# Per-user stats debug screen (jitter + ping) — Design

**Goal:** A dedicated debug screen that breaks the jitter/latency readout out **per remote user**
(name-attributed), plus each user's ping to the server, so a single misbehaving sender is
diagnosable instead of hidden inside an aggregate.

**Outcome:** New `PerUserStatsScreen` reached from the Audio diagnostics screen; per-speaker jitter
snapshot plumbed out of `AudioVoiceEngine`; per-user ping fetched via Mumble `UserStats`. No behavior
change to the audio path or the protocol beyond additive read-only diagnostics.

**Status:** design — **fable-reviewed + server-verified**. Fable confirmed the audio-side/concurrency
claims exact and caught two ping-side defects (reducer carry-forward, missing sender), both fixed
in-spec. The real-server behavior is now empirically settled against Murmur 1.5.901 (see "VERIFIED"):
ping works for non-admins and doesn't trip flood protection, but is **self-reported** — so a
`sendPing()` self-report step is now required. Grounded in the code as of `main@a099eb7`.

## Motivation (verified, not assumed)

On-device, a real conversation shows "wild 4000 ms" jitter values with no way to tell which speaker
is responsible. Root cause, verified in code:

- The diagnostics HUD reads `JitterStats.p95Ms`, which `AudioVoiceEngine.playbackLoop` computes as
  **`speakers.values.maxOfOrNull { it.jitterP95Ms() }`** (`AudioVoiceEngine.kt:374`) — a *max across
  all speakers*. One bad sender dominates the whole number.
- That per-speaker value is the estimator's **raw, unclamped** p95: `DownlinkJitterEstimator.p95Ms`
  is set at `DownlinkJitterEstimator.kt:99` (`p95Ms = (p95Ns / 1_000_000L).toInt()`) *before* the
  clamp on line 100.
- Crucially, the **actual** prebuffer (`targetSamples`) is clamped to **[10 ms, 400 ms]**
  (`FLOOR_NS`/`MAX_NS`, lines 120–121). So **4000 ms is a display value, not real buffering** — the
  buffer never holds 4 s. It is one speaker's delay-spike p95 leaking into an un-attributed aggregate.

The fix is therefore *attribution*, not clamping: keep the raw p95 (it is the real debug signal) but
show it **per speaker, by name**, alongside the other per-speaker jitter numbers and that user's ping.

## Architecture / data flow

Three additive slices, no change to existing audio or protocol behavior:

```
receive thread(s) ── SpeakerStream.offer ──► per-speaker lateDrops (@Volatile)
playback thread ──── playbackLoop (throttled ~500ms) ──► JitterStats{aggregate, perSpeaker[]}  ─┐
                                                                                                 ├─► UI
session dispatcher ─ SessionStateMachine.onFrame(UserStats) ─► MumbleModel per-user ping  ──────┘
UI (screen open) ─── DisposableEffect ─► MumbleManager.setUserStatsPolling(true) ─► periodic UserStats requests
```

The UI joins two sources **by session id**: the per-speaker jitter snapshot (audio engine) and the
user list with ping + name (Mumble model).

## Component A — per-speaker jitter snapshot

**Data model.**
- New **`data class`** `SpeakerJitter(session: Int, targetMs: Int, p95Ms: Int, bufferedMs: Int,
  lateDrops: Long)` — `data class` matters so the `perSpeaker` list gets structural equality and the
  `_jitter` `StateFlow` conflation keeps behaving as today (fable finding 6).
- Extend `JitterStats` with `perSpeaker: List<SpeakerJitter> = emptyList()`. Keep the existing
  aggregate `targetMs`/`p95Ms` fields, now **derived as the max over `perSpeaker`** (so every existing
  reader keeps working — verified there is exactly one: `AudioDiagnosticsScreen.kt:67-68`; empty-list
  default stays 10/0).

**`SpeakerStream` additions.**
- `fun bufferedMs(): Int = buffer.bufferedSamples() / 48`. `JitterBuffer.bufferedSamples()` is
  `@Synchronized` (`JitterBuffer.kt:59`), so this is safe from any thread.
- Per-speaker late-drop counter: `@Volatile var lateDrops: Long = 0L`, incremented inside `offer()`
  when `result == OfferResult.LATE && !isTerminator` — mirroring the engine's existing global rule
  (`AudioVoiceEngine.kt:321`) but attributed to the speaker. `offer()` already has `isTerminator` and
  the `OfferResult`. Writes happen under the existing `@Synchronized offer` (serializes the two
  possible receive threads — UDP recv + TCP-tunnel IO); `@Volatile` publishes the value to the
  playback-thread reader. The engine's global `lateDropCount` stays for the existing HUD.

**Snapshot (`AudioVoiceEngine.playbackLoop`).**
- Add a **new, separate** playback-thread tick counter — do NOT reuse `diagTick`/`DIAG_INTERVAL`,
  which are send-thread state (`AudioVoiceEngine.kt:47,202`); sharing them across threads would add a
  race that doesn't exist today (fable finding 5). Every ~25 ticks ≈ 500 ms, build the
  `perSpeaker` list from the live `speakers` map and publish a new `JitterStats` (with the aggregate
  derived from the list). Throttled so we do **not** allocate a list + N objects every 20 ms tick
  (today `_jitter` republishes every tick; moving it to the 500 ms cadence is fine for a HUD).
- All per-speaker reads are playback-thread-safe: `jitterTargetMs()`/`jitterP95Ms()` read
  `@Volatile` estimator fields, `playoutCursor()` reads the `@Volatile` cursor, `bufferedMs()` is
  `@Synchronized`, `lateDrops` is `@Volatile`. Iterating `speakers` (a `ConcurrentHashMap`) on the
  playback thread is already done in `playbackLoop`.
- Include not-yet-retired idle speakers (buffered 0) so you can see everyone producing streams.

## Component B — per-user ping via `UserStats`

The Mumble protocol exposes per-user ping through `UserStats` (proto confirmed:
`Mumble.proto:513`, fields `session`=1, `stats_only`=2, `udp_ping_avg`=8, `tcp_ping_avg`=10). It is
each user's ping **to the server** (server-measured), not peer-to-peer. `UserStats` is already in the
codec enum (`MumbleCodec.kt:14`, `UserStats(22)`).

**Request — needs a new sender (fable finding 2).** `channel: ControlChannel` is **private** in
`SessionStateMachine` (`SessionStateMachine.kt:53`); only narrow senders are exposed (`sendPing`,
`sendSelfMute`, `sendSelfDeaf`). So add, mirroring `sendSelfMute` (`SessionStateMachine.kt:179`):
`fun requestUserStats(session: Int) = channel.send(TcpMessageType.UserStats,
UserStats.newBuilder().setSession(session).setStatsOnly(true).build())`, plus an
`ActiveSession.requestUserStats(session) = sm.requestUserStats(session)` passthrough
(`MumbleManager.kt:442`-style). `stats_only = true` asks the server to send only the mutable
packet/ping stats (no cert chain) — cheap.

**Response handling.** `SessionStateMachine.onFrame` (`SessionStateMachine.kt:111`) gains:
`TcpMessageType.UserStats -> model.onUserStats(MumbleProtos.UserStats.parseFrom(frame.payload))`.
`onFrame` runs synchronously on that session's single `mumble-tcp-read` coroutine
(`MumbleTcpTransport.kt:83`, `Dispatchers.IO`, no suspension in the read loop → effectively
single-threaded), which is `MumbleModel`'s single writer (`MumbleModel.kt:79`) — so no new concurrency.

**Model — two edits, not one (fable finding 1, BLOCKER).** Add nullable `tcpPingMs: Float?` /
`udpPingMs: Float?` to `MumbleUser` (default null = unknown), plus `MumbleModel.onUserStats(stats)`
that writes them for `stats.session` (guarding `hasUdpPingAvg()`/`hasTcpPingAvg()`). **Critically**,
`ModelReducers.applyUserState` (`MumbleModel.kt:53-67`) reconstructs the entire `MumbleUser` on every
`UserState` message, threading each field forward via `old?.field ?: default`. It therefore MUST also
carry the ping forward — `tcpPingMs = old?.tcpPingMs, udpPingMs = old?.udpPingMs` — or the target
user's very next mute/channel-move/self-deaf (all common mid-call) rebuilds them to null and the ping
flickers to "—". This reducer edit is easy to miss because it's a *different* function from
`onUserStats`; both are required.

**Gated polling — cadence & permission are UNVERIFIED (fable finding 3); see "Open verification".**
While the Per-user stats screen is open, poll `UserStats` so we send **zero** extra protocol traffic
when nobody is viewing:
- `MumbleManager.setUserStatsPolling(enabled: Boolean)` starts/stops a coroutine that, while enabled,
  periodically requests `UserStats` and then republishes. Screen drives it via a Compose
  `DisposableEffect` (`onDispose { setUserStatsPolling(false) }`). If the call ends mid-poll, `active`
  is null and `active?.requestUserStats(...)` no-ops — graceful.
- **Do not** assume this is as cheap as the existing ping loop: that loop is **O(1)** (one `Ping`/5 s
  regardless of channel size, `MumbleManager.kt:405`); per-user polling is **O(N)** in channel
  population, sustained. A 20-person channel would be ~5 req/s continuously. **Defensive design until
  verified:** request only for the sessions actually shown/active (not the whole roster), cap the
  fan-out, and use a conservative interval (start ~5 s, back off if needed). Render "—" for any user
  whose ping never arrives, so the feature degrades gracefully if the server withholds it.

## Component C — UI

**`PerUserStatsScreen` (new).** Scaffold + back nav, matching `AudioDiagnosticsScreen`'s plain style:
- **Header:** server RTT once (existing `NetStats`: transport, `tcpRttMs`, `udpRttMs`, `udpJitterMs`).
- **Per-user list:** one row per user (from the model), showing name, ping `tcp/udp` (from model
  ping fields), and — joined by session from the jitter snapshot — `target` · **`p95 (raw)`** ·
  `buffered` · `late-drops`. Users with no active stream show ping only + "—" for jitter; speakers
  with no model entry (edge) fall back to `#session`. Empty state when the channel is silent.
- The "p95 (raw)" label makes explicit that it is the unclamped estimator p95 (distinct from the
  clamped `target`), so the "4000 ms" value reads as "that speaker's worst delay spike," not latency.

**Wiring.** Add a **"Per-user stats →"** row to `AudioDiagnosticsScreen` and a `showPerUserStats`
nav boolean in `DumbleApp` (mirrors the existing `showDiagnostics` pattern, `DumbleApp.kt:102`). The
new `showPerUserStats -> { … }` branch must be ordered **ahead of** `showDiagnostics` in the `when`
(same priority trick already used for `showDiagnostics`), so it renders over the diagnostics screen it
launched from (fable finding 7). Pass in the model users (name + ping, already at `DumbleApp.kt:49`),
`netStats`, and `jitter.perSpeaker`. Drive `setUserStatsPolling` from the screen's `DisposableEffect`.

## Threading & concurrency (load-bearing summary)

| State | Writer | Reader | Safe via |
|---|---|---|---|
| `SpeakerStream.lateDrops` | receive thread(s), inside `@Synchronized offer` | playback (snapshot) | `@Volatile` |
| `bufferedMs()` | receive (`offer`) | playback (snapshot) | `JitterBuffer` all `@Synchronized` |
| `jitterTargetMs/P95Ms`, `playoutCursor` | receive (estimator) / playback (cursor) | playback (snapshot) | `@Volatile` (existing) |
| `JitterStats` (perSpeaker) | playback (throttled) | UI (StateFlow) | conflated `StateFlow` (existing) |
| model ping fields | session dispatcher (`onUserStats`) | UI (StateFlow) | model single-writer (existing) |

## Testing

- `SpeakerStream`: a `LATE` non-terminator offer increments `lateDrops`; a `LATE` terminator and a
  `QUEUED`/`DUPLICATE` do not. `bufferedMs()` reflects queued span depth.
- `JitterStats`: aggregate `targetMs`/`p95Ms` equal the max over a given `perSpeaker` list (and the
  empty-list default is the existing 10/0).
- `MumbleModel.onUserStats`: writes `tcpPingMs`/`udpPingMs` for the target session; ignores fields the
  message doesn't set; unknown session is a no-op.
- Thread glue (the 500 ms snapshot, the 4 s poll loop) is kept as thin wrappers over the pure pieces
  above; not unit-tested directly.

## VERIFIED against real Murmur (2026-07-20)

Probe run against dockerized `mumblevoip/mumble-server` **1.5.901**, two anonymous (non-admin)
clients, via `LiveServerIntegrationTest.userStatsProbe`. Results:

1. **Permission — YES.** A non-admin client requesting `UserStats{peer, stats_only=true}` **receives a
   reply for the peer** with `hasTcpPingAvg`/`hasUdpPingAvg` present and no cert chain
   (`hasCerts=false`). Component B is viable; not permission-gated.
2. **Flood — safe.** 60 back-to-back `UserStats` requests → **no throttle, no disconnect**, all 60
   replied, connection stayed `Synchronized`. The intended ~5 s per-user cadence is far under any
   limit. (Still keep the defensive cap/interval — cheap insurance across server configs.)
3. **Ping is SELF-REPORTED, not server-measured (decisive).** With B self-reporting a sentinel
   `Ping{tcp_ping_avg=42, udp_ping_avg=43}`, A saw **exactly** `tcp=42.0 udp=43.0` in B's `UserStats`.
   Our current `sendPing()` (`SessionStateMachine.kt:165`) sets only `timestamp/good/late/lost/resync`
   — **never** `tcp_ping_avg`/`udp_ping_avg` — so **every Drumble peer reports 0.0 ping to others.**
   Desktop Mumble self-reports, so a Drumble client would see a *desktop* peer's real ping, but
   **Drumble↔Drumble would be all zeros** without the fix below.

**Consequence — Component B gains a required sub-step:** `sendPing()` must **self-report our own
measured RTT**: set `tcp_ping_avg` from the TCP RTT the state machine already computes
(`handlePingEcho`, `SessionStateMachine.kt:158`) and `udp_ping_avg` from the UDP RTT
(`TransportSelector.udpRttMs`, plumbed in). Small (~2 fields on the existing Ping builder from values
we already have) but **mandatory** for the ping column to be meaningful among Drumble clients. Nice
side effect: our ping also becomes visible to desktop Mumble users' info panels (fidelity win).

## Scope / non-goals

- **In:** per-speaker jitter breakout, per-user ping via `UserStats` (gated polling), dedicated
  screen.
- **Out (deferred, TODO'd):** per-user *volume* adjustment; peer-to-peer ping (does not exist in
  Mumble); making the screen work without a live call.
- **No** change to the adaptive jitter algorithm, the clamp, or any audio timing. This is read-only
  diagnostics.

## Files

- `mumble/voice/JitterStats.kt` — add `SpeakerJitter`, `perSpeaker`, derived aggregate.
- `mumble/voice/SpeakerStream.kt` — `bufferedMs()`, per-speaker `lateDrops`.
- `mumble/voice/AudioVoiceEngine.kt` — throttled per-speaker snapshot into `_jitter`.
- `mumble/protocol/SessionStateMachine.kt` — `UserStats` response case in `onFrame`, a new
  `requestUserStats(session)` sender (channel is private; finding 2), **and** `sendPing()` self-report
  of `tcp_ping_avg`/`udp_ping_avg` (server-verified requirement — else Drumble↔Drumble ping is 0).
- `mumble/model/MumbleModel.kt` — per-user ping fields, `onUserStats`, **and** the `applyUserState`
  carry-forward (finding 1, BLOCKER).
- `mumble/MumbleManager.kt` — `ActiveSession.requestUserStats` passthrough + `setUserStatsPolling`
  poll loop; expose ping/model to UI (existing).
- `app/src/test/java/.../integration/LiveServerIntegrationTest.kt` — the `UserStats` probe (Open
  verification).
- `ui/PerUserStatsScreen.kt` (new), `ui/AudioDiagnosticsScreen.kt` (entry row), `ui/DumbleApp.kt` (nav).
