package me.danielstiner.dumble.mumble.voice

const val SAMPLE_RATE = 48000
const val CHANNELS = 1

/** Playback quantum. 10 ms is Mumble's minimum audio-per-packet, so our granularity never
 *  coarsens what a sender chose. Correctness does not depend on it — SpeakerQueue's PCM FIFO
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
 *  a way to fill [MAX_SPEAKERS] with 60 ms of audio apiece and deny voice to everyone real. */
const val STALL_IDLE_TICKS = 1000

/** Concurrent per-speaker queues we will allocate, bounding what a server can make us hold at
 *  34,560 bytes of Java arrays each plus a native decoder. The desktop client has no equivalent
 *  cap and is genuinely unbounded here — its `ClientUser` lookup gates on the session list, which
 *  a hostile server writes via UserState, so it bounds nothing. What keeps that survivable is retiring on drain:
 *  every live slot has to be re-fed within [RETIRE_IDLE_TICKS] or it evaporates, so holding N of
 *  them costs sustained bandwidth rather than one packet apiece. This cap is the hard ceiling on
 *  top of that pricing. Sized far above any plausible channel population, so it bites only on a
 *  server inventing sessions. */
const val MAX_SPEAKERS = 64
