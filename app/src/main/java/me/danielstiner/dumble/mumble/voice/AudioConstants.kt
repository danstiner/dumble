package me.danielstiner.dumble.mumble.voice

/** Mumble voice is 48 kHz mono; these pin the playback/decode side to the protocol. The native
 *  capture side pins itself independently (CaptureConstants.h kSampleRate/kChannels) — deliberate
 *  twins, not a shared definition; see the note there. */
const val SAMPLE_RATE = 48000
const val CHANNELS = 1

/** Playback quantum. 10 ms is Mumble's minimum audio-per-packet, so our granularity never
 *  coarsens what a sender chose. Correctness does not depend on it — SpeakerPlayout's PCM FIFO
 *  decouples quantum from packet duration — so this is tunable. */
const val QUANTUM_SAMPLES = 480

/** One packet may carry up to 60 ms; the decode scratch buffer must hold the largest legal
 *  Opus frame (120 ms) so a malformed or unusually long packet cannot overrun it. */
const val MAX_FRAME_SAMPLES = 5760

/** Playout margin armed at the start of each talk spurt. TCP removes reordering and loss but
 *  not head-of-line burstiness, and a pipeline fed at exactly 1x real time carries no margin
 *  of its own — without this, the first retransmit stall glitches. */
const val PREBUFFER_SAMPLES = 2880

/** ~600 ms. Only reachable if playback has stalled outright; expressed in samples because
 *  packet duration is the sender's choice, so a packet count would mean 320 ms from a 10 ms
 *  sender and 1.9 s from a 60 ms one. */
const val HIGH_WATER_SAMPLES = 28800

/** Idle ticks after a speaker's queue has drained before its decoder is released, matching the
 *  desktop client — AudioOutputSpeech retires on `iMissCount > 10`, roughly 100 ms, rather than
 *  holding native decoder state for seconds. Ticks, not milliseconds: the playback loop is paced
 *  by AudioTrack at ~100 Hz while anyone is producing but runs faster when nobody is, since every
 *  arriving packet wakes it, so this is a ~50-100 ms window rather than an exact one. */
const val RETIRE_IDLE_TICKS = 10

/** Backstop window for a spurt that stalled below [PREBUFFER_SAMPLES] and never got a terminator
 *  — a sender that died mid-spurt. It produces nothing and never drains, so the short window
 *  above can never apply to it and the slot would be held for the life of the connection. That is
 *  a way to fill [MAX_SPEAKERS] with 60 ms of audio apiece and deny voice to everyone real.
 *
 *  ~1 s, matching the native engine's kStallIdleTicks, and a ceiling rather than a period: the
 *  playback loop parks 10 ms at a time while any speaker is live, and every arriving packet wakes
 *  it early. Sized from what the fragment is worth, not from how long a link can stall — the most
 *  this window can protect is the sub-60 ms of audio sitting below the prebuffer gate, and audio
 *  spliced in a second after it was spoken is heard as a click, not as speech. */
const val STALL_IDLE_TICKS = 100

/** Concurrent per-speaker queues we will allocate, bounding what a server can make us hold at
 *  ~28 KB of Java arrays each (an 8192-short ring plus the decode scratch) plus a native
 *  decoder. The desktop client has no equivalent cap and is genuinely unbounded here — its
 *  `ClientUser` lookup gates on the session list, which a hostile server writes via UserState,
 *  so it bounds nothing. What keeps that survivable is retiring on drain: every live slot has
 *  to be re-fed within [RETIRE_IDLE_TICKS] or it evaporates, so holding N of them costs
 *  sustained bandwidth rather than one packet apiece. This cap is the hard ceiling on top of
 *  that pricing.
 *
 *  Kept equal to the native engine's kMaxSpeakers (PlayoutConstants.h) — the intelligibility
 *  bound: past about eight overlapping voices nothing is intelligible anyway. A wider gate here
 *  would admit sessions whose every native offer() answers kOfferSpeakerCap once voice flows
 *  through PlayoutEngine: audio silently dropped rather than refused at the door. */
const val MAX_SPEAKERS = 8
