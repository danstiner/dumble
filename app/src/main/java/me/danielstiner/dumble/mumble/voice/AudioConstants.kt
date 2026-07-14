package me.danielstiner.dumble.mumble.voice

const val SAMPLE_RATE = 48000
const val CHANNELS = 1
const val FRAME_SAMPLES_20MS = 960
const val FRAME_SAMPLES_10MS = 480
const val MAX_FRAME_SAMPLES = 5760
/** 10 ms sub-frames per outgoing packet. Single knob for a future 10/20/40/60 ms packet-size
 *  slider (see spec); all gate timing is in 10 ms ticks so it stays packet-size-invariant. */
const val FRAMES_PER_PACKET = 2
/** Samples captured & encoded per outgoing packet (FRAMES_PER_PACKET x 10 ms). 960 at N=2. */
const val CAPTURE_SAMPLES = FRAMES_PER_PACKET * FRAME_SAMPLES_10MS
const val MAX_ENCODED_BYTES = 4000
const val OPUS_APPLICATION_VOIP = 2048
