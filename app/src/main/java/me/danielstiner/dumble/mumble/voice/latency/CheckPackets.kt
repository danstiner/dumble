package me.danielstiner.dumble.mumble.voice.latency

import kotlin.time.ComparableTimeMark

/** A voice packet capture produced on a latency check's session. [at] is when the pump handed it
 *  over, on the clock [ArrivedPacket.at] shares; [opusData] is the check's own copy. */
class CapturedPacket(
    val frameNumber: Long,
    val opusData: ByteArray,
    val at: ComparableTimeMark,
)

/** A voice packet from the server that the playout engine accepted on a latency check's session.
 *  [at] is when it reached the engine. [aheadSamples] is what the engine held for [session] before
 *  it, queued or decoded and not yet played: the wait ahead of this packet. A spurt's gate waits
 *  for [targetSamples] plus [writeAheadSamples]; a wait well past their sum is a burst that
 *  arrived at once. */
class ArrivedPacket(
    val session: Int,
    val frameNumber: Long,
    val at: ComparableTimeMark,
    val aheadSamples: Int,
    val targetSamples: Int,
    val writeAheadSamples: Int,
)
