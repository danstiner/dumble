#pragma once
#include <cstdint>

namespace dumble {

// Mumble voice is 48 kHz mono; these pin the capture/encode side to the protocol. The playback
// side pins itself independently (AudioConstants.kt SAMPLE_RATE/CHANNELS) — deliberate twins,
// not a shared definition: nothing crosses the JNI boundary at runtime to keep them aligned,
// each side answers to the protocol, and single-sourcing two ints across languages would cost
// codegen or runtime lookups. int because libopus, their main consumer here, takes int.
constexpr int kSampleRate = 48000;
constexpr int kChannels = 1;

// 20 ms. Upstream Mumble's own default (iFramesPerPacket = 2), and the largest single Opus frame
// — 40 and 60 ms are multi-frame packets. Below 20 ms SILK loses coding efficiency and per-packet
// overhead doubles, for ~10 ms of algorithmic delay against a budget already carrying a 60 ms
// receiver prebuffer.
constexpr int kTxFrameSamples = 960;

// 10 ms, the unit MumbleUDP.Audio.frame_number is counted in.
constexpr int kFrameNumberUnitSamples = kSampleRate / 100;   // 480

// 341 ms. Power of two so index wrapping is a mask.
constexpr uint32_t kRingCapacitySamples = 16384;

// Consumer-side staleness bound: beyond this, drop forward to the newest frame.
constexpr uint32_t kHighWaterSamples = 4800;                 // 100 ms

// pollFrame return codes. Non-negative values are byte counts.
constexpr int kPollRetry = -1;      // stream is down, native side is reopening — keep polling
constexpr int kPollShutdown = -2;   // stop() was called — exit the loop

constexpr uint32_t kFlagTerminator = 1u;

}  // namespace dumble
