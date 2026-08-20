# Audio playout

Incoming voice packets to mixed PCM on the speaker. Three layers, mirroring capture
(`docs/capture.md`): a platform-free native engine, a thin JNI seam, and one Kotlin thread that
owns the engine's lifetime. Details live in the code's comments; tuning constants and their
reasoning in `PlayoutConstants.h`.

```
transport ─► onTunneledAudio ─► offer() ─► PacketQueue ─► SpeakerDecoder ─► Mixer ─► fillQuantum ─► AudioTrack
             (reader coroutine)            └────────── PlayoutEngine (core/) ──────────┘           (playback thread)
```

**PlayoutEngine** (`core/`) owns one `PacketQueue` and one `SpeakerDecoder` per sender. Its only
clock is the `fillQuantum` call, shaped as an audio data callback — non-blocking, allocation-free —
so an Oboe output stream could drive it; today a Kotlin thread does, between `AudioTrack` writes.
Packets wait compressed behind a per-speaker prebuffer gate so a network stall cannot glitch the
opening syllable, and the mix finalizes through `Mixer`'s soft-knee limiter.

Two ownership rules carry the design:

- `offer()`, on the reader thread, touches queues only, under the mutex; decode and mix happen on
  the playback thread with the mutex released. No refusal in `offer` is terminal — each is a
  condition a misbehaving server can produce at will, so the caller logs rather than disabling
  receive.
- A speaker's slot is released only by retirement, inside `fillQuantum`, never in `offer` —
  neither side of the seam can tell a speaker that stopped talking from one still filling its
  prebuffer, so the engine alone decides.

**JNI seam** (`android/playout_jni.cpp`): one `jlong` handle. Data crosses on stack scratch so no
GC-visible pin is held across the engine's mutex or the mix, and multi-value answers are flattened
into primitive arrays whose layouts are named by `NativePlayout`'s constants.

**Playback loop** (`VoiceReceiver`): one daemon thread, paced by the blocking `AudioTrack` write
while any speaker is draining — the audio clock is the only clock, so no timer exists or should —
and parked otherwise. The engine exists iff the loop is running, and only the playback thread
frees it; `stop()` never does, so a loop wedged in a blocked write cannot be raced by its own
teardown.

Invariants are pinned by `PlayoutEngineTest.cpp`, `PacketQueueTest.cpp`, `VoiceReceiverTest.kt`,
`NativePlayoutTest.kt` and `PlayoutLoopDeviceTest.kt`.
