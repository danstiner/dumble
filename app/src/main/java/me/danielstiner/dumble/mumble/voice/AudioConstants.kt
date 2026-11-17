package me.danielstiner.dumble.mumble.voice

/** Mumble voice is 48 kHz mono; this pins the playback side to the protocol. The native capture
 *  side pins itself independently (CaptureConstants.h kSampleRate) — deliberate twins, not a
 *  shared definition; see the note there. The playout engine takes its rate through
 *  PlayoutEngine::create, so it has nothing of its own to drift. */
const val SAMPLE_RATE = 48000

/** One 10 ms frame at [SAMPLE_RATE], the unit Mumble's `frame_number` counts in. Nothing in the
 *  app reads it; the device tests count queued audio in it. */
const val FRAME_SAMPLES = 480

/** Twin of the native engine's kMaxSpeakers (PlayoutConstants.h), where the reasoning for the
 *  number lives: the intelligibility bound, past which nothing is intelligible anyway. The engine
 *  caps concurrent speakers, so what this bounds on this side is allocation — it sizes the
 *  arrays the receiver's poll hands across the JNI seam for stats, and the seam validates their
 *  lengths against native's own cap. Undersize them and every call is refused, so these must
 *  not drift. */
const val MAX_SPEAKERS = 8

/** Twin of the voice-activity gate's kHangoverFrames (CaptureConstants.h), in milliseconds: how
 *  long a spurt runs on once speech stops, and so how long the speaking halo takes to fade. */
const val HANGOVER_MILLIS = 200
