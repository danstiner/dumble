#pragma once
#include <cstdint>

namespace dumble {

// Deliberate twin of AudioConstants.kt SAMPLE_RATE: nothing crosses JNI at runtime to keep them
// aligned, and each side answers to the protocol.
constexpr int kSampleRate = 48000;
constexpr int kChannels = 1;

// 10 ms — the unit Mumble counts in. Durations are counts of these.
constexpr int kFrameSamples = kSampleRate / 100;   // 480

// Mumble's iAudioFrames. Below 20 ms SILK loses coding efficiency and per-packet overhead doubles.
constexpr int kFramesPerPacket = 2;

// 20 ms, and the largest single Opus frame libopus will encode in one call.
constexpr int kTxPacketSamples = kFrameSamples * kFramesPerPacket;   // 960

// Power of two so index wrapping is a mask.
constexpr uint32_t kRingCapacitySamples = 16384;              // 341 ms

// Consumer-side staleness bound: beyond this, drop forward to the newest frame.
constexpr uint32_t kHighWaterSamples = 4800;                  // 100 ms

// onPcm() does not signal the pump (audio callback must not block), so this bounds notice latency.
constexpr int kPollWaitMillis = kTxPacketSamples / (kSampleRate / 1000) / 4;   // 5 ms

// libopus ceiling (opus_encoder.c). Typical 32 kb/s packet is ~80 bytes.
constexpr int kMaxPacketBytes = 1276;

// pollPacket return codes. Non-negative values are byte counts.
constexpr int kPollRetry = -1;          // stream down, native is reopening
constexpr int kPollShutdown = -2;       // stop() was called
constexpr int kPollUnavailable = -3;    // terminal: native gave up reopening
constexpr int kPollNoSession = -4;      // null handle
constexpr int kPollBufferTooSmall = -5; // out array too small

constexpr uint32_t kFlagTerminator = 1u;

// Hysteresis + hangover; sweep results in docs/capture.md.
constexpr float kOpenLevel = 0.60f;
constexpr float kCloseLevel = 0.45f;
constexpr int kHangoverFrames = 20;   // 200 ms

// 60 ms burst (6 frames) at gate-open. Covers the ~62-70 ms onset blind spot -- not the 40 ms
// structural bound -- with nothing to spare, not with margin. See docs/capture.md.
constexpr int kPrerollPackets = 3;

}  // namespace dumble
