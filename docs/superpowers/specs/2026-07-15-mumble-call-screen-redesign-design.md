# Mumble Call Screen Redesign (imported "Mumble Call" design) — Design

**Date:** 2026-07-15
**Status:** Approved (brainstorm), pending implementation plan
**Feature:** Replace the minimal in-call screen with the imported "Mumble Call" Claude Design — a
themed screen with a **live channel/user tree** (speaking indicators, mute badges), a richer control
bar (**Mute / Deafen / Speaker / Leave**), and settings via a "tune" icon.

Sub-project **3 of 4** in the audio-quality effort. Design source: claude.ai/design project
`c132b758-b439-43d9-9bc4-3ece4084edd4`, file `Mumble Call.dc.html`. Related specs: (1) transmit
activation-mode selector — this screen hosts its Push-to-Talk control; (2) automatic gain control;
(4) a CI utterance corpus for VAD gate/hysteresis testing.

---

## Goal

Render the current call as the imported design: server/root-channel header with a connection timer,
a scrollable tree of channels and their users with **live speaking** and **mute** state, and a
four-button control bar. Add **self-deafen** as a new capability. Keep audio routing (Speaker) as a
first-class control.

## Scope

**In v1:**
- Themed call screen (top bar + channel tree + bottom control card) matching the design tokens below.
- Live channel/user tree from `MumbleModel` (users grouped by channel; own/active channel highlighted).
- Per-user visual state: colored initial-avatar, **live speaking** ring + equalizer bars, **self-mute
  (grey) / server-mute (red)** badges, **YOU** tag.
- New engine signals for live speaking (`speakingSessions`, `selfTransmitting`).
- **Deafen** (self-deafen): mute playback + imply self-mute + broadcast to server.
- Bottom control bar: **Mute / Deafen / Speaker / Leave**; in Push-to-Talk mode the Mute slot becomes
  **Hold-to-Talk** (spec 1). Speaker stays a one-tap route toggle.
- Settings entry via the **tune** icon (replaces the plain gear added earlier).

**Out (deferred):**
- **Tap-to-switch-channels** (sending `UserState.channel_id`) — follow-up.
- **Nested-channel indentation** — v1 renders a flat, position-ordered list.
- **Mock-only elements with no Mumble protocol backing:** the **BOT** tag, the **AFK "away"** moon,
  and **per-channel icons** (the mock's campaign/esports/bedtime). Real Mumble channels have no icon
  and the protocol has no bot/away flag. v1 omits BOT/away and uses **one generic channel glyph**.
- **Dark mode** — the design is light-only; v1 applies the light palette, dark mode is future.

---

## Verified: how "we are talking" reaches other clients (no new message)

Fable-verified against `mumble-voip/mumble` @ master (2026-07-15). **The Mumble protocol has no
"talking"/"speaking" control message** — `Mumble.proto`'s `UserState` (23 fields incl. `mute, deaf,
suppress, self_mute, self_deaf, priority_speaker, recording`) has no speech-activity field. A user is
shown as talking on other clients **purely because those clients are receiving that user's voice
packets**:
- `ServerHandler::handleVoicePacket` attributes audio by `senderSession`; `AudioOutputSpeech` →
  `ClientUser::setTalking` drives the remote UI from the decode path, never a control handler.
- The indicator clears on the **`is_terminator`** bit (`MumbleUDP.Audio` field 16 / legacy `0x2000`)
  or, if that's missing, a ~100 ms+ jitter-buffer starvation timeout.
- Receivers schedule by **`frame_number`** (`jbp.timestamp = iFrameSize * frameNumber`); a
  non-incrementing / wildly-jumping sequence is discarded as late → no audio, no indicator.
- The **server** stamps `senderSession`; the sender does not send its own id.

**Consequence for this spec:** our own speaking indicator on other clients needs **no new code** — it
is a consequence of the existing transmit path. Its two dependencies are already satisfied by prior
fixes: **real (non-empty) terminators** (prompt clear + clean fade-out) and **wall-clock
`frame_number`** (correct scheduling). The new plumbing below is only for **our own** UI.

---

## Real-data mapping

| Mock element | Source | v1 |
|---|---|---|
| Channels + users | `MumbleModel.state`: `channels` (order by `position`), `users` grouped by `channelId` | ✅ real |
| Server/header name | **root channel** name (the `parentId == null` / id-0 channel), fallback to config host | ✅ real |
| "YOU" tag | `user.session == serverModel.sessionId` | ✅ real |
| Self-mute badge (grey) | `MumbleUser.selfMute` | ✅ real |
| Server-mute badge (red) | `MumbleUser.mute` | ✅ real |
| Speaking ring + eq bars | new engine signals (below) | ✅ real |
| Connection timer | elapsed since `ConnectionState.Synchronized` | ✅ real |
| BOT tag | *no protocol flag* | ❌ omit |
| AFK "away" moon | *no protocol away state* | ❌ omit |
| Per-channel icons | *no protocol channel icon* | ❌ one generic glyph |

Deafen indicator: `selfDeaf`/`deaf` also available in the model — v1 may show a deafened badge on
remote users (cheap add) but it is not required.

---

## Architecture / new plumbing

### Live speaking signals (`AudioVoiceEngine` → `MumbleManager`)
- **Downlink:** `speakingSessions: StateFlow<Set<Int>>` — sessions whose `SpeakerStream` produced
  audio this playback tick, held for ~200 ms after the last produced frame so the indicator doesn't
  strobe on the ~20 ms tick cadence. Updated in `playbackLoop` (which already computes the active
  set each tick).
- **Uplink (self):** `selfTransmitting: StateFlow<Boolean>` — true while `nextOutgoingFrame` returns
  a real (non-terminator) send, false on the closing/idle frame, with the same ~200 ms release hold.
- `MumbleManager` re-exposes both (mapping the engine's flows through the active session, resetting to
  empty/false when no session).

### Deafen (`setDeafened(Boolean)`)
- `MumbleManager.setDeafened(true)`: mute playback output (engine writes silence / `AudioTrack`
  volume 0 while deafened, but keeps draining streams so jitter buffers stay sane), **imply
  self-mute** (`setMuted(true)`), and broadcast `UserState{self_deaf = true, self_mute = true}`.
- `setDeafened(false)`: clear self-deaf and self-mute, broadcast `UserState{self_deaf = false,
  self_mute = false}`, restore playback.
- Engine gains `@Volatile deafened` read in `playbackLoop`. New `sm.sendSelfDeaf(...)` alongside the
  existing `sendSelfMute`.

### State assembly (`CallScreenState`)
A pure mapper combines `MumbleModel.state` + `speakingSessions` + `selfTransmitting` + local
`muted`/`deafened`/`activationMode` into an ordered list of channels, each with an ordered list of
user view-models (`initial`, `avatarColor`, `name`, `isYou`, `speaking`, `selfMute`, `serverMute`).
Lives in `DumbleApp` (or a small `CallViewModel`); JVM-unit-testable in isolation.

---

## UI composition (design tokens)

Light palette applied to the call screen (local color tokens or a `DumbleTheme` extension):
`app bg #FBF8FF`, `control card #F1ECF8` (radius 28 top), `container/idle #E5DEFF`, `accent #5B4CCB`,
`accent-strong #17064B` / `#43318F`, `text #1B1B21`, `muted-text #48454E`, `speaking-green #2E6B2A`,
`error #BA1A1A`, `error-container #FFDAD6` / `on #8C0009`. Icons via Material Icons (extended where
needed): `mic`/`mic_off`, `headphones`/`headset_off` (deafen), `call_end` (leave), `tune` (settings),
a generic channel glyph, plus the app-bar `headset_mic`.

- **Top app bar:** 46×46 rounded icon tile (`#E5DEFF`, `headset_mic`), server/root-channel name
  (21sp), a `#2E6B2A` dot + "Connected · mm:ss", and a right-aligned **tune** icon → Settings.
- **Channel tree** (scroll): for each channel containing ≥1 user (plus always your own), a header
  (generic glyph + uppercase name, accent when it's your channel) and its user rows. Row (min-height
  62dp): 42dp initial-avatar (deterministic color from session/name), speaking ring
  (`2.5px #2E6B2A` + glow) + 3 animated eq bars when speaking, self/server mute badge (21dp, grey vs
  red), name (16sp), **YOU** chip (`#5B4CCB` on `#E5DEFF`) on your row.
- **Bottom control bar** (4 controls, 64×64 tiles radius 22, red 72×64 Leave): VAD mode →
  **Mute / Deafen / Speaker / Leave**; PTT mode → **Hold-to-Talk / Deafen / Speaker / Leave**
  (the Talk button uses `detectTapGestures(onPress = { onPttDown(); tryAwaitRelease(); onPttUp() })`,
  filled while held). Mute red when muted; Deafen red when deafened; Speaker reflects route state.

---

## Components / files

- **New:** `ui/CallScreenState.kt` (state assembly + user/channel view-models + deterministic avatar
  color), `ui/CallTheme.kt` (design color tokens) — or fold tokens into `theme/`.
- **Rewrite:** `ui/ActiveCallScreen.kt` — the full redesigned layout; params extend to
  `serverName, connectedSince, channels: List<ChannelVm>, muted, deafened, speaker, activationMode,
  onToggleMute, onToggleDeafen, onToggleSpeaker, onHangUp, onOpenSettings, onPttDown, onPttUp`.
- **Modify:** `AudioVoiceEngine.kt` (`speakingSessions`, `selfTransmitting`, `deafened` + playback
  muting); `MumbleManager.kt` (re-expose speaking flows; `setDeafened`; `ActiveSession.setDeafened`;
  `sm.sendSelfDeaf`); the session state machine (`sendSelfDeaf`); `ui/DumbleApp.kt` (collect model +
  speaking flows, assemble `CallScreenState`, pass to `ActiveCallScreen`, wire deafen).
- **Reference:** design tokens above; canonical design at the claude.ai/design project id in the header.

---

## Edge cases

- **Own row placement:** the design floats "you" at the top of your channel — v1 keeps natural order
  but tags YOU; floating-self is a cheap refinement, not required.
- **Deafen ↔ mute coupling:** deafen forces mute; un-deafen clears mute. A manual unmute while
  deafened is disallowed (deafen wins) — the Mute control is disabled/red while deafened.
- **Speaking hold vs terminator:** the ~200 ms hold means the local indicator lingers briefly after a
  talkspurt — matches the design's fade feel and the receiver-side behavior we verified.
- **Empty/other channels:** channels with no users are hidden (except your own) to match the mock's
  populated look; nesting is flattened.
- **No session / connecting:** show the connecting state (existing behavior) until `Synchronized`.

---

## Testing

**Unit (JVM):**
- `CallScreenState` assembly: users grouped by channel and ordered; YOU tag on own session; server
  name resolves to the root channel then config host; mute/server-mute mapping.
- `speaking()` derivation: own session uses `selfTransmitting`, remote uses `speakingSessions`; the
  ~200 ms release hold keeps a just-stopped speaker "speaking" for the hold window then clears.
- Deafen implies mute and clears on un-deafen; deafened engine writes silence while still draining.
- Deterministic avatar color is stable per session/name.

**On-device (user acceptance gate):** join a multi-user channel and confirm the tree renders with
correct membership; the speaking ring/eq animate on the actual talker (and on yourself while
transmitting); self-mute vs server-mute badges are correct; **Deafen** silences playback and shows
you self-muted/deafened to peers; **Speaker** toggles the route; in PTT mode the **Hold-to-Talk**
button replaces Mute and gates transmission. Cross-check with a second client that your own speaking
indicator appears there while you talk and clears promptly when you stop (validates the verified
terminator path end-to-end).

---

## Interaction with other sub-projects

- **Spec 1 (activation selector):** this screen is the home for the Hold-to-Talk control (Mute→Talk in
  PTT mode). Spec 1's engine/settings land independently; its call-screen button folds into this
  layout. Build order: this redesign settles the layout, then spec 1's button slots in.
- **Spec 4 (VAD utterance corpus):** the cross-client speaking-indicator check above is manual;
  spec 4 adds the automated end-to-end assertion that a full utterance passes the gate.
