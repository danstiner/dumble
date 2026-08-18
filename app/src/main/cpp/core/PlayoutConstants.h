#pragma once
#include <cstdint>

namespace dumble::playout {

// A nested namespace, not bare `dumble`: CaptureConstants.h already owns kHighWaterSamples and
// kMaxPacketBytes at that scope, with different values for the transmit side.

// Concurrent speakers we mix — the intelligibility bound itself: past about eight overlapping
// voices nothing is intelligible anyway. A slot is also held by a speaker draining its last
// packets (kRetireIdlePolls) and by one stalled below the prebuffer gate (kStallIdlePolls, ~1 s),
// so a channel with rapid turn-taking can hold more slots than it has talkers and briefly park a
// real speaker at kOfferSpeakerCap — accepted: the held slots retire on their own inside a
// second, and a mix that crowded is already unintelligible.
// The desktop client has no equivalent cap. It bounds the memory but not the denial —
// parking every slot costs one packet per session per second, which the server we connected to
// can trivially afford, and every real speaker then gets kOfferSpeakerCap. No untrusted peer can
// reach it, and the server could mute us outright anyway.
constexpr int kMaxSpeakers = 8;

// 120 ms at 48 kHz, the largest legal Opus packet, so a malformed or unusually long packet cannot
// overrun the decode scratch. The sibling of kMaxPacketBytes: same object, one bound in samples and
// one in bytes. Packet and not frame — the largest single Opus frame is the 20 ms kTxPacketSamples,
// and 40 ms and up are multi-frame packets.
constexpr int kMaxPacketSamples = 5760;

// libopus's concealment granularity, 2.5 ms at 48 kHz: opus_decode answers OPUS_BAD_ARG for a
// frame_size off this grid when it is concealing rather than decoding.
constexpr int kConcealGridSamples = 120;

// 60 ms. Playout margin armed at the start of each talk spurt. TCP removes reordering and loss but
// not head-of-line burstiness, and a pipeline fed at exactly 1x real time carries no margin of its
// own — without this, the first retransmit stall glitches.
constexpr int kPrebufferSamples = 2880;

// 600 ms. Only reachable if playback has stalled outright. In samples because packet duration is
// the sender's choice, so a packet count would mean 320 ms from a 10 ms sender and 1.9 s from a
// 60 ms one.
constexpr int kHighWaterSamples = 28800;

// Polls after a speaker's queue has drained before its decoder and pool are released. A poll is a
// fillQuantum call that produced nothing, so the loop parks rather than writing and these outrun real
// time — this is a ceiling on fills, not a duration. Matches the desktop client, which retires on
// AudioOutputSpeech's `iMissCount > 10`.
constexpr int kRetireIdlePolls = 10;

// Quanta of concealment a speaker gets when its queue starves mid-spurt, before we give up on the
// spurt and let it re-anchor. Concealment counts as production, so each of these is written to the
// output and paced by it — quanta, not polls, and therefore a real ~100 ms at today's quantum
// rather than a count of fills. Matches the desktop client's AudioOutputSpeech miss count.
//
// libopus fades its concealment to near-silence over ~60 ms (see SpeakerDecoder::conceal),
// so the number is really how long the speaker's playout anchor is held open: past ~100 ms the
// break has been heard as one, and re-anchoring with a fresh prebuffer beats splicing across it.
constexpr int kConcealQuanta = 10;

// Backstop for a spurt that stalled below kPrebufferSamples and never got a terminator — a sender
// that died mid-spurt. It produces nothing and never drains, so the short window can never apply
// to it and the slot would be held for the life of the connection.
//
// ~1 s at best, and a ceiling rather than a period: these are polls, which outrun real time. Sized
// from what the fragment is worth, not from how long a link can stall — the most this window can
// protect is the sub-60 ms of audio sitting below the prebuffer gate, and audio spliced in a second
// after it was spoken is heard as a click, not as speech.
constexpr int kStallIdlePolls = 100;

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
