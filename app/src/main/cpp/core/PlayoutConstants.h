#pragma once
#include <cstdint>

namespace dumble::playout {

// A nested namespace, not bare `dumble`: CaptureConstants.h already owns kHighWaterSamples and
// kMaxPacketBytes at that scope, with different values for the transmit side.

// Concurrent speakers we mix — the intelligibility bound itself: past about eight overlapping
// voices nothing is intelligible anyway. A slot is also held by a speaker draining its last
// packets (kRetireIdleSamples) and by one stalled below the prebuffer gate (kStallIdleSamples),
// so a channel with rapid turn-taking can hold more slots than it has talkers and briefly park a
// real speaker at kOfferSpeakerCap — accepted: the held slots retire on their own inside a
// second, and a mix that crowded is already unintelligible.
// The desktop client has no equivalent cap. It bounds the memory but not the denial —
// parking every slot costs one packet per session per second, which the server we connected to
// can trivially afford, and every real speaker then gets kOfferSpeakerCap. No untrusted peer can
// reach it, and the server could mute us outright anyway.
constexpr int kMaxSpeakers = 8;

// 48 kHz, the one rate the whole playout path assumes — Mumble is 48 kHz end to end. A constant
// rather than the engine's runtime sampleRate_ so sample counts stay compile-time: durations are
// the tuned quantities, and the sample counts derive from them.
constexpr int kSamplesPerMilli = 48;

// The largest legal Opus packet, so a malformed or unusually long packet cannot overrun the
// decode scratch. The sibling of kMaxPacketBytes: same object, one bound in samples and one in
// bytes. Packet and not frame — the largest single Opus frame is the 20 ms kTxPacketSamples,
// and 40 ms and up are multi-frame packets.
constexpr int kMaxPacketSamples = 120 * kSamplesPerMilli;

// libopus's concealment granularity, 2.5 ms at 48 kHz: opus_decode answers OPUS_BAD_ARG for a
// frame_size off this grid when it is concealing rather than decoding.
constexpr int kConcealGridSamples = 120;

// Only reachable if playback has stalled outright. In samples because packet duration is the
// sender's choice, so a packet count would mean 320 ms from a 10 ms sender and 1.9 s from a
// 60 ms one.
constexpr int kHighWaterSamples = 600 * kSamplesPerMilli;

// Samples of silence after a speaker's queue has drained before its decoder and pool are
// released. Matches the desktop client, which retires on AudioOutputSpeech's `iMissCount > 10`
// at 10 ms a miss.
constexpr int kRetireIdleSamples = 100 * kSamplesPerMilli;

// Samples of concealment a speaker gets when its queue starves mid-spurt, before we give up on
// the spurt and let it re-anchor. libopus fades its concealment to near-silence over ~60 ms (see
// SpeakerDecoder::conceal), so the number is really how long the speaker's playout anchor is
// held open: past ~100 ms the break has been heard as one, and re-anchoring with a fresh
// prebuffer beats splicing across it.
constexpr int kConcealSamples = 100 * kSamplesPerMilli;

// Backstop for a spurt that stalled below the target and never got a terminator — a sender
// that died mid-spurt. It produces nothing and never drains, so the short window can never apply
// to it and the slot would be held for the life of the connection. Sized from what the fragment
// is worth, not from how long a link can stall: audio spliced in a second after it was spoken is
// heard as a click, not as speech.
constexpr int kStallIdleSamples = 1000 * kSamplesPerMilli;

// Packets a speaker's queue can hold at once. 32 is 320 ms from a 10 ms sender and 1.9 s from a
// 60 ms one, so kHighWaterSamples binds first at every packet duration of 20 ms and above.
// Deliberately tighter than kHighWaterSamples for a 10 ms sender: a stall long enough to strand
// more than 320 ms has already produced an audible gap, and playing the whole backlog converts
// that gap into standing latency instead of removing it.
constexpr int kMaxQueuedPackets = 32;

// Per-slot payload capacity, and the threshold above which offer() answers kOfferPacketTooLarge.
// 1276 is libopus's own ceiling on encoder output for any packet (opus_encoder.c,
// `max_data_bytes = IMIN(orig_max_data_bytes, 1276)`), which makes the refusal exactly "no
// conforming encoder produced this" instead of an arbitrary number with slack. Matches
// dumble::kMaxPacketBytes on the capture side. The Opus format itself permits larger packets;
// nothing that could send one is a peer we serve.
constexpr int kMaxPacketBytes = 1276;

// The unit MumbleUDP.Audio.frame_number counts in. Sibling of CaptureConstants.h's
// kFrameSamples, in milliseconds because the estimator's arithmetic is millisecond-wide:
// nanoseconds times a sample rate overflows int64 at plausible boot times.
constexpr int kFrameNumberMillis = 10;

// Sliding-minimum bucket. Two are kept, so the baseline is the minimum over 1-2 s of arrivals —
// NetEq's kPacketHistorySizeMs is 2000 over an exact deque, which at 64 tracked senders would be
// 12800 entries to answer a running minimum. Two buckets answer it in O(1) state.
constexpr int kBaselineBucketMillis = 1000;

// Past this a frame number times kFrameNumberMillis stops being worth reasoning about, and it is
// peer-controlled. ~317 years of 10 ms frames, so no honest sender reaches it.
constexpr uint64_t kMaxFrameNumber = 1'000'000'000'000'000ULL;

// One histogram update per 500 ms of arrivals, carrying that window's worst relative delay.
// NetEq's resample_interval_ms. The pair (peak-hold interval, forget factor) is what sets the
// estimate's time constant, so neither may be changed without the other: 0.983 per update at
// 500 ms per update is roughly a 29 s memory.
constexpr int kPeakHoldMillis = 500;

// 32 buckets of 20 ms — 640 ms of range. NetEq uses 100 buckets to reach 2000 ms; kHighWaterSamples
// caps us at 600, so the rest would never be addressed.
constexpr int kTargetBucketMillis = 20;
constexpr int kTargetBuckets = 32;

// The 95th percentile of the histogram, as a fraction. Integer so the quantile search needs no
// float.
constexpr int kTargetQuantileNumerator = 95;
constexpr int kTargetQuantileDenominator = 100;

// 0.983 in Q15, NetEq's forget_factor. Buckets are scaled so they always sum to 1<<30.
constexpr int kForgetFactorQ15 = 32211;

// Ramps the effective forget factor in from zero over the first updates, so a cold estimator
// follows its first arrivals instead of averaging them against an empty histogram.
constexpr int kStartForgetWeight = 2;

// Added to the quantile before clamping. Mumble's own margin (Settings.h iJitterBufferSize = 1,
// times a 10 ms frame), and in the same additive role: speexdsp subtracts it from every timing
// sample rather than treating it as a floor.
constexpr int kSafetyMarginMillis = 10;

// A safety rail, not the operating point. On a clean link the lowest bucket already answers 20 ms,
// so with the margin the target settles near 30 and the floor never binds.
constexpr int kMinTargetMillis = 10;

// 75 % of kHighWaterSamples — NetEq's rule that a target must stay clear of the buffer's
// capacity, and derived so it tracks that capacity if it moves. It is not the tighter bound:
// kMaxQueuedPackets binds first for any sender below 20 ms (32 packets is 320 ms at 10 ms/packet,
// under this 450 ms clamp), so such a sender's ring can never physically reach this target. That
// is safe only because PacketQueue::pop treats a full ring as gate-open regardless of target —
// without that check this clamp would leave the gate shut, and every further packet dropped as
// overflow, for as long as the target stayed high.
constexpr int kMaxTargetMillis = kHighWaterSamples / kSamplesPerMilli * 3 / 4;

// NetEq's kStartDelayMs. 20 ms worse than the fixed 60 ms margin it replaces, for the first
// spurt of the first speaker in a session and nothing after — the engine-wide histogram seeds
// every later newcomer. Kept at NetEq's measured value rather than tuned to match the old
// behaviour, because we have no measurement of our own yet.
constexpr int kColdStartMillis = 80;
constexpr int kColdStartSamples = kColdStartMillis * kSamplesPerMilli;

// How far past the target a gate-open may sit before the backlog is trimmed rather than carried.
// The same 100 ms span and the same reasoning as kConcealSamples: past about that long the break
// has already been heard as one, so splicing the pre-gap audio back in gains a listener little
// and costs standing delay for the rest of the spurt. Generous on purpose — an ordinary gate-open
// sits at the target plus at most one packet, so the trim is a no-op and only a real overshoot
// reaches it.
constexpr int kCatchUpThresholdSamples = 100 * kSamplesPerMilli;

// Hysteresis on the shrink test. Overshoot is prevented by canShrink's arithmetic, not by this;
// all it does is stop the depth oscillating across the target between quiet windows.
constexpr int kShrinkDeadbandSamples = 20 * kSamplesPerMilli;

// 2 s between shrinks, counted only on fills that produced.
constexpr int kShrinkCooldownSamples = 2000 * kSamplesPerMilli;

// Senders whose estimate we keep. Not kMaxSpeakers: 8 is the simultaneous-mixing bound, but a
// channel has many more members than that and turn-taking cycles through them, so the table wants
// everyone who has spoken recently. 64 entries is about 11 KB, allocated once.
constexpr int kEstimatorSlots = 64;

// offer() status codes. Every one is a condition a misbehaving server can produce at will, so the
// caller latches its logs rather than treating them as failures.
constexpr int kOfferAccepted = 0;
constexpr int kOfferSpeakerCap = 1;
constexpr int kOfferPacketTooLarge = 2;
constexpr int kOfferMalformedPacket = 3;

// fillQuantum() error code, negative so it cannot collide with a speaker count.
// The caller's buffers, not ours — separate from "no audio" (return 0), because a caller that
// cannot tell "I allocated my buffers too small" from "nobody is speaking" has hidden a bug in the
// Kotlin side behind an orderly-looking silence. See CaptureConstants.h for the same reasoning and
// kPollNoSession / kPollBufferTooSmall.
constexpr int kErrorBufferTooSmall = -1;

}  // namespace dumble::playout
