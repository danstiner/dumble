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
// overhead doubles.
constexpr int kTxFrameSamples = 960;

// 10 ms, the unit MumbleUDP.Audio.frame_number is counted in.
constexpr int kFrameNumberUnitSamples = kSampleRate / 100;   // 480

// 341 ms. Power of two so index wrapping is a mask.
constexpr uint32_t kRingCapacitySamples = 16384;

// Consumer-side staleness bound: beyond this, drop forward to the newest frame.
constexpr uint32_t kHighWaterSamples = 4800;                 // 100 ms

// How long the pump parks between polls. onPcm() deliberately does not signal it — the audio
// callback stays free of anything that could block — so this interval alone decides how late a
// finished frame is noticed. A quarter of a packet keeps that under 5 ms against the 20 ms the
// packet spends filling, at 200 mostly-empty wakes a second while transmitting.
constexpr int kPollWaitMillis = kTxFrameSamples / (kSampleRate / 1000) / 4;   // 5 ms

// The largest packet opus_encode can return for one frame: libopus caps its own output there
// (`max_data_bytes = IMIN(orig_max_data_bytes, 1276)`, opus_encoder.c), and opus.h documents 1276
// as the buffer size for a single frame. Every 20 ms frame is a single-frame packet, so this is a
// ceiling and not an estimate — a 32 kb/s packet is nearer 80 bytes.
constexpr int kMaxPacketBytes = 1276;

// pollFrame return codes. Non-negative values are byte counts.
constexpr int kPollRetry = -1;        // stream is down, native side is reopening — keep polling
constexpr int kPollShutdown = -2;     // stop() was called — exit the loop
// The platform adapter exhausted its reopen attempts. Distinct from kPollRetry — a caller that
// can't tell "still trying" from "never coming back" would poll a stream that will not recover
// for the rest of the session; this is a stop() and a user-visible "transmit unavailable" moment.
constexpr int kPollUnavailable = -3;
// Broken-caller codes. Separate from kPollShutdown, which they used to be folded into, because a
// pump that exits on shutdown has done the right thing and one that exits on either of these has
// hidden a bug in the Kotlin side behind an orderly-looking stop.
constexpr int kPollNoSession = -4;      // null handle: create() failed, or destroy() already ran
constexpr int kPollBufferTooSmall = -5; // `out` cannot hold a largest-case packet

constexpr uint32_t kFlagTerminator = 1u;

}  // namespace dumble
