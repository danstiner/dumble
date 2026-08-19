#pragma once
#include <cstdint>

namespace dumble {

// Deliberate twins of the playback side's AudioConstants.kt SAMPLE_RATE, not a shared definition:
// nothing crosses JNI at runtime to keep them aligned, and each side answers to the protocol.
constexpr int kSampleRate = 48000;
constexpr int kChannels = 1;

// 10 ms — the unit this codebase and upstream Mumble both count in (AudioInput's
// `iFrameSize = SAMPLE_RATE / 100`, and MumbleUDP.Audio.frame_number). Durations are counts of these.
constexpr int kFrameSamples = kSampleRate / 100;   // 480

// Mumble's iAudioFrames. Below 20 ms SILK loses coding efficiency and per-packet overhead doubles.
constexpr int kFramesPerPacket = 2;

// 20 ms, and the largest single Opus frame libopus will encode in one call.
constexpr int kTxPacketSamples = kFrameSamples * kFramesPerPacket;   // 960

// Power of two so index wrapping is a mask.
constexpr uint32_t kRingCapacitySamples = 16384;              // 341 ms

// Consumer-side staleness bound: beyond this, drop forward to the newest frame.
constexpr uint32_t kHighWaterSamples = 4800;                  // 100 ms

// onPcm() deliberately does not signal the pump — the audio callback stays free of anything that
// could block — so this interval alone decides how late a finished packet is noticed.
constexpr int kPollWaitMillis = kTxPacketSamples / (kSampleRate / 1000) / 4;   // 5 ms

// A ceiling, not an estimate: libopus caps its own output here
// (`max_data_bytes = IMIN(orig_max_data_bytes, 1276)`, opus_encoder.c). A 32 kb/s packet is ~80.
constexpr int kMaxPacketBytes = 1276;

// pollPacket return codes. Non-negative values are byte counts.
constexpr int kPollRetry = -1;        // stream is down, native side is reopening — keep polling
constexpr int kPollShutdown = -2;     // stop() was called — exit the loop
// Terminal, unlike kPollRetry: a caller that cannot tell "still trying" from "never coming back"
// would poll a dead stream for the rest of the session.
constexpr int kPollUnavailable = -3;
// Broken-caller codes, kept out of kPollShutdown so a bug on the Kotlin side cannot hide behind an
// orderly-looking stop.
constexpr int kPollNoSession = -4;      // null handle: create() failed, or destroy() already ran
constexpr int kPollBufferTooSmall = -5; // `out` cannot hold a largest-case packet

constexpr uint32_t kFlagTerminator = 1u;

// Two thresholds so a level at the boundary cannot chatter the gate, and a hangover in frames so
// the hold is a duration rather than a packet count (Mumble's iHoldFrames).
//
// Swept against the labelled corpus (2026-08-18): 60 candidates, all scoring zero false openings,
// zero missed regions and zero dropout. The grid saturated, so the data ranked exactly one thing —
// degradation starts spreading at open 0.70 — and could not separate open within {0.40, 0.50, 0.60},
// any close gap, or any hangover. Clean read speech cannot measure false-activation risk. 0.60 is
// the highest open the corpus does not penalise; re-ranking the rest needs noisy material.
constexpr float kOpenLevel = 0.60f;
constexpr float kCloseLevel = 0.45f;
constexpr int kHangoverFrames = 20;   // 200 ms

// Flushed as a burst at gate-open, covering the onset the detector cannot see until the first
// inference whose window contains it — 40 ms worst case, the longest gap between inferences, so
// 60 ms clears it with margin. Sized from the detector, never from a receive-side constant: an
// earlier draft called three packets "exactly the receiver's prebuffer", which stopped being true
// the moment an adaptive jitter buffer replaced the fixed one.
constexpr int kPrerollPackets = 3;

}  // namespace dumble
