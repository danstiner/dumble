package me.danielstiner.dumble.mumble.voice

/**
 * How the client decides to transmit.
 *  - [VOICE_ACTIVATED]: the RNNoise transmit gate opens/closes on voice activity (the default).
 *  - [PUSH_TO_TALK]: transmit only while the user holds the on-screen button; the gate is bypassed
 *    (audio is still denoised) so onsets are never clipped and quiet speech still goes through.
 */
enum class TransmitMode { VOICE_ACTIVATED, PUSH_TO_TALK }
