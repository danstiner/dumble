package me.danielstiner.dumble.mumble.voice

/** Mumble voice is 48 kHz mono; this pins the playback side to the protocol. The native capture
 *  side pins itself independently (CaptureConstants.h kSampleRate) — deliberate twins, not a
 *  shared definition; see the note there. The playout engine takes its rate through
 *  PlayoutEngine::create, so it has nothing of its own to drift. */
const val SAMPLE_RATE = 48000

/** Playback quantum. 10 ms is Mumble's minimum audio-per-packet, so our granularity never
 *  coarsens what a sender chose. Correctness does not depend on it — the native mixer's PCM ring
 *  decouples quantum from packet duration — so this is tunable. */
const val QUANTUM_SAMPLES = 480

/** Twin of the native engine's kMaxSpeakers (PlayoutConstants.h), where the reasoning for the
 *  number lives: the intelligibility bound, past which nothing is intelligible anyway. Concurrent
 *  speakers are the engine's to cap now, so what this bounds on this side is allocation — it sizes
 *  the arrays the playback loop hands across the JNI seam, and the seam validates their lengths
 *  against native's own cap. Undersize them and every call is refused, so these must not drift. */
const val MAX_SPEAKERS = 8
