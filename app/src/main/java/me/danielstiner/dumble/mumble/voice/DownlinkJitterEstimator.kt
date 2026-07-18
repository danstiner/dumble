package me.danielstiner.dumble.mumble.voice

/**
 * Per-speaker downlink jitter estimator. Produces an adaptive prebuffer [targetSamples] and a
 * mid-spurt [lateBurst] signal from the speaker's recent relative arrival delay. Pure (no Android
 * deps) → JVM-testable. Fed on the RECEIVE thread via [onPacket]; [targetSamples]/[lateBurst]/[p95Ms]
 * are @Volatile and read on the PLAYBACK thread (single-writer publication, JMM 17.7 safe).
 *
 * Model (design doc): d = arrivalNanos − rtpNanos; relative delay = d − min(d over the live window)
 * (offset/skew-cancelling); peak-held into 200 ms buckets over an 8 s window; target = p95 of the
 * live bucket peaks, clamped to [10 ms, 400 ms]. Mirrors NetEq's underrun_optimizer minus the
 * forgetting histogram.
 *
 * Preconditions: [onPacket] is called from a single (receive) thread, and rtpSamples is continuous —
 * call [reset] on a frame_number discontinuity / stream restart. [lateBurst] is only valid while packets
 * are flowing (it is recomputed per packet, not cleared during silence).
 */
class DownlinkJitterEstimator {
    // Bucket ring: each 200 ms slot holds min/max of d, or is empty. Receive-thread-only state.
    private val bucketMinD = LongArray(WINDOW_BUCKETS)
    private val bucketMaxD = LongArray(WINDOW_BUCKETS)
    private val bucketFilled = BooleanArray(WINDOW_BUCKETS)
    private var newestEpoch = Long.MIN_VALUE
    private val peaksScratch = LongArray(WINDOW_BUCKETS)   // reused; no hot-path alloc

    // Late-burst ring: arrivalNanos of the last LATE_BURST_COUNT late packets.
    private val lateRing = LongArray(LATE_BURST_COUNT)
    private var lateHead = 0
    private var lateSeen = 0

    @Volatile var targetSamples: Int = FLOOR_SAMPLES
        private set
    @Volatile var lateBurst: Boolean = false
        private set
    @Volatile var p95Ms: Int = 0
        private set

    /** Feed one arriving voice packet (receive thread). [wasLate] = JitterBuffer returned LATE. */
    fun onPacket(rtpSamples: Long, arrivalNanos: Long, wasLate: Boolean) {
        val rtpNanos = rtpSamples * 125_000L / 6   // exact: 1e9/48000 = 125000/6 (robust to any frame size)
        val d = arrivalNanos - rtpNanos
        val epoch = Math.floorDiv(arrivalNanos, BUCKET_NS)

        advanceTo(epoch)
        if (epoch <= newestEpoch - WINDOW_BUCKETS) return   // ancient reorder: would alias a live slot — ignore
        val idx = Math.floorMod(epoch, WINDOW_BUCKETS.toLong()).toInt()
        if (!bucketFilled[idx]) {
            bucketMinD[idx] = d; bucketMaxD[idx] = d; bucketFilled[idx] = true
        } else {
            if (d < bucketMinD[idx]) bucketMinD[idx] = d
            if (d > bucketMaxD[idx]) bucketMaxD[idx] = d
        }

        if (wasLate) {
            lateRing[lateHead] = arrivalNanos
            lateHead = (lateHead + 1) % LATE_BURST_COUNT
            if (lateSeen < LATE_BURST_COUNT) lateSeen++
        }
        lateBurst = lateSeen >= LATE_BURST_COUNT && countLateWithin(arrivalNanos) >= LATE_BURST_COUNT

        recomputeTarget()
    }

    /** Advance the ring so [epoch] is newest; clear each newly-entered (aged-out) slot. Older/reordered
     *  epochs are kept (they still update their existing bucket above). */
    private fun advanceTo(epoch: Long) {
        if (newestEpoch == Long.MIN_VALUE) { newestEpoch = epoch; return }
        if (epoch <= newestEpoch) return
        if (epoch - newestEpoch >= WINDOW_BUCKETS) {   // large gap: clear all once, don't loop per-epoch
            java.util.Arrays.fill(bucketFilled, false)
            newestEpoch = epoch
            return
        }
        var e = newestEpoch + 1
        while (e <= epoch) {
            bucketFilled[Math.floorMod(e, WINDOW_BUCKETS.toLong()).toInt()] = false
            e++
        }
        newestEpoch = epoch
    }

    private fun countLateWithin(nowArrival: Long): Int {
        var count = 0
        for (t in lateRing) if (t != 0L && nowArrival - t <= LATE_WINDOW_NS) count++
        return count
    }

    private fun recomputeTarget() {
        var baseline = Long.MAX_VALUE
        for (i in 0 until WINDOW_BUCKETS) if (bucketFilled[i] && bucketMinD[i] < baseline) baseline = bucketMinD[i]
        if (baseline == Long.MAX_VALUE) { targetSamples = FLOOR_SAMPLES; p95Ms = 0; return }
        var n = 0
        for (i in 0 until WINDOW_BUCKETS) if (bucketFilled[i]) {
            peaksScratch[n++] = (bucketMaxD[i] - baseline).coerceAtLeast(0L)
        }
        java.util.Arrays.sort(peaksScratch, 0, n)
        val idx = Math.max(0, Math.ceil(0.95 * n).toInt() - 1)
        val p95Ns = peaksScratch[idx]
        p95Ms = (p95Ns / 1_000_000L).toInt()
        val clampedNs = p95Ns.coerceIn(FLOOR_NS, MAX_NS)
        targetSamples = (clampedNs * 48L / 1_000_000L).toInt()   // ns → samples (48 samples/ms)
    }

    /** Reset to cold. Call on stream restart / re-anchor: a frame_number discontinuity would otherwise mix
     *  two baselines and pin the target at MAX for up to the window length. */
    fun reset() {
        java.util.Arrays.fill(bucketFilled, false)
        newestEpoch = Long.MIN_VALUE
        java.util.Arrays.fill(lateRing, 0L)
        lateHead = 0
        lateSeen = 0
        targetSamples = FLOOR_SAMPLES
        lateBurst = false
        p95Ms = 0
    }

    companion object {
        const val BUCKET_NS = 200_000_000L         // 200 ms peak-hold slot
        const val WINDOW_BUCKETS = 40              // 8 s window
        const val FLOOR_NS = 10_000_000L           // 10 ms
        const val MAX_NS = 400_000_000L            // 400 ms target cap
        const val LATE_WINDOW_NS = 200_000_000L    // late-burst window
        const val LATE_BURST_COUNT = 3             // >= 3 LATE in the window
        val FLOOR_SAMPLES = (FLOOR_NS * 48L / 1_000_000L).toInt()   // 480
    }
}
