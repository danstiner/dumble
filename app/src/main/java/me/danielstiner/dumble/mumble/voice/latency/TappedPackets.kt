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
 *  it arrived. [queuedSamples] and [decodedSamples] are what the engine held for [session] before
 *  it: the wait ahead of this packet. */
class ArrivedPacket(
    val session: Int,
    val frameNumber: Long,
    val at: ComparableTimeMark,
    val queuedSamples: Int,
    val decodedSamples: Int,
)
