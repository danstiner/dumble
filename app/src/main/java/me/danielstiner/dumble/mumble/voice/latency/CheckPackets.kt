package me.danielstiner.dumble.mumble.voice.latency

import kotlin.time.ComparableTimeMark

/** A voice packet from the server that the playout engine accepted on a latency check's session.
 *  [at] is when it reached the engine. [aheadSamples] is what the engine held for [session] before
 *  it, queued or decoded and not yet played: the wait ahead of this packet. */
class ArrivedPacket(
    val session: Int,
    val frameNumber: Long,
    val at: ComparableTimeMark,
    val aheadSamples: Int,
)
