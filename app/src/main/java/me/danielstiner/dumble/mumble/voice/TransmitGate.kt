package me.danielstiner.dumble.mumble.voice

/**
 * Turns per-10ms-sub-frame speech levels into one per-capture transmit decision.
 *
 * Two-threshold hysteresis (open at [openLevel], stay open above [closeLevel]) plus a
 * time-based hangover counted in 10 ms sub-frame ticks ([maxHoldTicks]) — so the hold
 * DURATION is invariant to packet size (mirrors Mumble's iHoldFrames, which counts 10 ms
 * frames). A single voiced sub-frame in a capture keeps the whole capture transmitting; the
 * silent half rides along as lead-in/tail.
 *
 * While transmitting, send=true. On the closing capture (transmission just stopped), BOTH
 * send=true and terminator=true — the closing (silent) frame is sent as a REAL terminator, not
 * an empty packet (Mumble drops empty-payload packets before reading the terminator flag). Then
 * idle: send=false, terminator=false.
 */
class TransmitGate(
    val openLevel: Float = 0.60f,
    private val closeLevel: Float = 0.35f,
    private val maxHoldTicks: Int = 20,   // 20 x 10 ms = 200 ms hangover
) {
    data class Decision(val send: Boolean, val terminator: Boolean)

    private var transmitting = false
    private var holdTicks = 0

    /** @param levels one entry per 10 ms sub-frame of the capture (size == FRAMES_PER_PACKET). */
    fun update(levels: FloatArray): Decision {
        val wasTransmitting = transmitting
        for (lvl in levels) {
            val raw = if (transmitting) lvl > closeLevel else lvl > openLevel
            if (raw) {
                holdTicks = 0
                transmitting = true
            } else if (transmitting) {
                if (++holdTicks >= maxHoldTicks) transmitting = false
            }
        }
        return when {
            transmitting -> Decision(send = true, terminator = false)
            wasTransmitting -> Decision(send = true, terminator = true)   // just closed: send real terminator
            else -> Decision(send = false, terminator = false)
        }
    }

    fun reset() { transmitting = false; holdTicks = 0 }
}
