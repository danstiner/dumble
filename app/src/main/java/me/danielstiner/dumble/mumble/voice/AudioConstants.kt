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

/** ~10 s of silence at the 10 ms quantum before a speaker's decoder is released. */
const val RETIRE_IDLE_TICKS = 1000

/** Concurrent per-speaker queues we will allocate, bounding what a server can make us hold at
 *  ~50 KB each (PCM FIFO, decode scratch, native decoder). Sized well above any plausible count
 *  of distinct people talking inside one retirement window, so it bites only on a broken or
 *  hostile server rather than on a busy channel. */
const val MAX_SPEAKERS = 32
