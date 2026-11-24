package me.danielstiner.dumble.mumble.voice.latency

import kotlin.time.ComparableTimeMark

/** A voice packet capture produced, seen by a latency check. [at] is when `pollPacket` returned it,
 *  on the clock [ArrivedPacket.at] shares; [opusData] is a copy. */
class CapturedPacket(
    val frameNumber: Long,
    val opusData: ByteArray,
    val at: ComparableTimeMark,
)

/** A voice packet from the server that the engine accepted, seen by a latency check. [at] is when
 *  it arrived. [aheadSamples] is what the engine held for [session] before it, queued or decoded
 *  and not yet played: the wait ahead of this packet. */
class ArrivedPacket(
    val session: Int,
    val frameNumber: Long,
    val at: ComparableTimeMark,
    val aheadSamples: Int,
)
