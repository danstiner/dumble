package me.danielstiner.dumble.mumble.voice.latency

import kotlin.time.ComparableTimeMark

/** A voice packet capture produced, seen by a latency check. [at] is when `pollPacket` returned it,
 *  on the clock [ArrivedPacket.at] shares; [opusData] is a copy. */
class CapturedPacket(
    val frameNumber: Long,
    val opusData: ByteArray,
    val at: ComparableTimeMark,
)

/** A voice packet as it arrived from the server, seen by a latency check before the engine took
 *  it. [queuedSamples] and [decodedSamples] are what the engine already held for [session] then:
 *  the wait ahead of this packet. */
class ArrivedPacket(
    val session: Int,
    val frameNumber: Long,
    val at: ComparableTimeMark,
    val queuedSamples: Int,
    val decodedSamples: Int,
)
