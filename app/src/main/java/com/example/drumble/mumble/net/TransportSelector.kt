package com.example.drumble.mumble.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceTransportMode { UDP, TCP_TUNNEL }

data class NetStats(
    val mode: VoiceTransportMode = VoiceTransportMode.UDP,
    val tcpRttMs: Double = -1.0,
    val udpRttMs: Double = -1.0,
    val udpJitterMs: Double = 0.0,
    val good: Int = 0, val late: Int = 0, val lost: Int = 0, val resync: Int = 0,
    val remoteGood: Int = 0, val remoteLate: Int = 0, val remoteLost: Int = 0, val remoteResync: Int = 0,
)

/**
 * Delta-based UDP<->tunnel policy, evaluated once per ping tick (~5 s):
 * counters stalled 2 consecutive ticks while we're sending -> tunnel;
 * counters flowing again (UDP pings still run while tunneled) -> back to UDP.
 */
class TransportSelector(private val forceTcp: Boolean) {
    private val _stats = MutableStateFlow(NetStats(
        mode = if (forceTcp) VoiceTransportMode.TCP_TUNNEL else VoiceTransportMode.UDP))
    val stats: StateFlow<NetStats> = _stats.asStateFlow()
    val mode: VoiceTransportMode get() = _stats.value.mode

    private var prevGood = 0
    private var prevRemoteGood = 0
    private var stallTicks = 0
    private var udpJitter = 0.0
    private var lastUdpRtt = -1.0

    @Synchronized fun evaluate(c: CryptState.Stats, sendingVoice: Boolean) {
        val goodDelta = c.good - prevGood
        val remoteDelta = c.remoteGood - prevRemoteGood
        prevGood = c.good; prevRemoteGood = c.remoteGood

        val current = _stats.value.mode
        val next = if (forceTcp) VoiceTransportMode.TCP_TUNNEL else when {
            current == VoiceTransportMode.UDP && sendingVoice && (goodDelta == 0 || remoteDelta == 0) -> {
                if (++stallTicks >= 2) VoiceTransportMode.TCP_TUNNEL else current
            }
            current == VoiceTransportMode.TCP_TUNNEL && goodDelta > 0 && remoteDelta > 0 -> {
                stallTicks = 0; VoiceTransportMode.UDP
            }
            else -> { if (goodDelta > 0 && remoteDelta > 0) stallTicks = 0; current }
        }
        _stats.value = _stats.value.copy(
            mode = next,
            good = c.good, late = c.late, lost = c.lost, resync = c.resync,
            remoteGood = c.remoteGood, remoteLate = c.remoteLate,
            remoteLost = c.remoteLost, remoteResync = c.remoteResync,
        )
    }

    @Synchronized fun onTcpRtt(ms: Double) { _stats.value = _stats.value.copy(tcpRttMs = ms) }

    @Synchronized fun onUdpPong(rttMs: Double) {
        if (lastUdpRtt >= 0) {
            val d = kotlin.math.abs(rttMs - lastUdpRtt)
            udpJitter += (d - udpJitter) / 16.0 // RFC3550-flavored smoothing
        }
        lastUdpRtt = rttMs
        _stats.value = _stats.value.copy(udpRttMs = rttMs, udpJitterMs = udpJitter)
    }
}
