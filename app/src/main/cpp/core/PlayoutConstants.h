#pragma once
#include <cstdint>

namespace dumble::playout {

// A nested namespace, not bare `dumble`: CaptureConstants.h already owns kHighWaterSamples and
// kMaxPacketBytes at that scope, with different values for the transmit side.

// Concurrent speakers we mix, and — since PlayoutEngine builds them all up front — what the
// playout path costs in memory. Past about eight overlapping voices nothing is intelligible
// anyway, so this is sized for the tail rather than for the mix: a slot is also held by a speaker
// draining its last packets (kRetireIdleTicks) and by one stalled below the prebuffer gate
// (kStallIdleTicks, ~1 s), so a channel with rapid turn-taking holds more slots than it has
// talkers. The desktop client has no equivalent cap. It bounds the memory but not the denial —
// parking every slot costs one packet per session per second, which the server we connected to
// can trivially afford, and every real speaker then gets kOfferSpeakerCap. No untrusted peer can
// reach it, and the server could mute us outright anyway.
constexpr int kMaxSpeakers = 16;

// 120 ms at 48 kHz, the largest legal Opus packet, so a malformed or unusually long packet cannot
// overrun the decode scratch. The sibling of kMaxPacketBytes: same object, one bound in samples and
// one in bytes. Packet and not frame — the largest single Opus frame is the 20 ms kTxFrameSamples,
// and 40 ms and up are multi-frame packets.
constexpr int kMaxPacketSamples = 5760;

// 60 ms. Playout margin armed at the start of each talk spurt. TCP removes reordering and loss but
// not head-of-line burstiness, and a pipeline fed at exactly 1x real time carries no margin of its
// own — without this, the first retransmit stall glitches.
constexpr int kPrebufferSamples = 2880;

// 600 ms. Only reachable if playback has stalled outright. In samples because packet duration is
// the sender's choice, so a packet count would mean 320 ms from a 10 ms sender and 1.9 s from a
// 60 ms one.
constexpr int kHighWaterSamples = 28800;

// Idle ticks after a speaker's queue has drained before its decoder and pool are released,
// matching the desktop client — AudioOutputSpeech retires on `iMissCount > 10`, roughly 100 ms.
// Ticks, not milliseconds: the playback loop is paced by AudioTrack at ~100 Hz while anyone is
// producing but runs faster when nobody is, since every arriving packet wakes it.
constexpr int kRetireIdleTicks = 10;

// Backstop for a spurt that stalled below kPrebufferSamples and never got a terminator — a sender
// that died mid-spurt. It produces nothing and never drains, so the short window can never apply
// to it and the slot would be held for the life of the connection.
//
// ~1 s, and a ceiling rather than a period: the playback loop parks 10 ms at a time while any
// speaker is live, and every arriving packet wakes it early. Sized from what the fragment is worth,
// not from how long a link can stall — the most this window can protect is the sub-60 ms of audio
// sitting below the prebuffer gate, and audio spliced in a second after it was spoken is heard as
// a click, not as speech.
constexpr int kStallIdleTicks = 100;

// Preallocated packet slots per speaker. 32 slots is 320 ms from a 10 ms sender and 1.9 s from a
// 60 ms one, so kHighWaterSamples binds first at every packet duration of 20 ms and above.
// Deliberately tighter than kHighWaterSamples for a 10 ms sender: a stall long enough to strand
// more than 320 ms has already produced an audible gap, and playing the whole backlog converts
// that gap into standing latency instead of removing it.
constexpr int kPacketSlots = 32;

// Per-slot payload capacity, and the threshold above which offer() answers kOfferPacketTooLarge.
// 1276 is libopus's own ceiling on encoder output for any packet (opus_encoder.c,
// `max_data_bytes = IMIN(orig_max_data_bytes, 1276)`), which makes the refusal exactly "no
// conforming encoder produced this" instead of an arbitrary number with slack. Matches
// dumble::kMaxPacketBytes on the capture side. The Opus format itself permits larger packets;
// nothing that could send one is a peer we serve.
constexpr int kMaxPacketBytes = 1276;

// offer() status codes. Only kOfferEngineUnusable is terminal for the session; the others are
// conditions a misbehaving server can produce at will, so the caller latches its logs rather than
// treating them as failures.
//
// kOfferMalformedPacket is the one that needs a code of its own rather than folding into the
// dropped-packet counter: a peer sending nothing but unparseable payloads otherwise looks exactly
// like a legitimate burst overflowing the queue bounds, and every offer answers kOfferAccepted
// while the audio goes nowhere.
constexpr int kOfferAccepted = 0;
constexpr int kOfferSpeakerCap = 1;
constexpr int kOfferPacketTooLarge = 2;
constexpr int kOfferEngineUnusable = 3;
constexpr int kOfferMalformedPacket = 4;

// fillQuantum() and readStats() error code, negative so it cannot collide with a speaker count.
// The caller's buffers, not ours — separate from "no audio" (return 0), because a caller that
// cannot tell "I allocated my buffers too small" from "nobody is speaking" has hidden a bug in the
// Kotlin side behind an orderly-looking silence. See CaptureConstants.h for the same reasoning and
// kPollNoSession / kPollBufferTooSmall.
constexpr int kErrorBufferTooSmall = -1;

// Layout of the single int array the JNI seam flattens fillQuantum's two outputs into: live speaker
// count at index 0, producing sessions from index 1 up. One array is one region copy per tick.
// Direct C++ callers take the two as separate parameters and never see this.
constexpr int kStatusActiveSpeakers = 0;


}  // namespace dumble::playout
