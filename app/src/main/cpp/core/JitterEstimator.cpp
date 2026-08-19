#include "core/JitterEstimator.h"

namespace dumble::playout {

int JitterEstimator::observe(uint64_t frameNumber, int samples, int64_t arrivalMillis) {
    if (frameNumber > kMaxFrameNumber) {
        // Peer-controlled, and the multiplication below is where an absurd value would stop
        // meaning anything. Dropping the anchor makes the next packet a rebase.
        haveFrame_ = false;
        sawDiscontinuity_ = true;
        return -1;
    }
    const bool contiguous = haveFrame_ && frameNumber == nextFrameNumber_;
    if (!contiguous) sawDiscontinuity_ = true;
    haveFrame_ = true;
    // A terminator carries no samples, so it advances nothing and the next real packet reads as a
    // jump — which is the right answer, since a terminator is the end of a spurt.
    // Integer division truncates a 2.5 or 5 ms packet's contribution to 0, so a sender using either
    // duration never advances nextFrameNumber_ and every packet after its first reads as
    // discontinuous: permanently cold-start target, catch-up never allowed. Safe rather than
    // correct — cold start and no catch-up are exactly what a genuinely discontinuous stream
    // wants — but such a sender never earns the benefit of a jitter estimate.
    nextFrameNumber_ = frameNumber + uint64_t(samples / (kFrameNumberMillis * kSamplesPerMilli));

    const int64_t offset = arrivalMillis - int64_t(frameNumber) * kFrameNumberMillis;
    const bool stale = arrivalMillis - bucketStartMillis_ >= 2 * kBaselineBucketMillis;
    if (!contiguous || !haveCurrent_ || stale) {
        currentMin_ = offset;
        haveCurrent_ = true;
        havePrevious_ = false;
        bucketStartMillis_ = arrivalMillis;
        return -1;
    }
    if (arrivalMillis - bucketStartMillis_ >= kBaselineBucketMillis) {
        previousMin_ = currentMin_;
        havePrevious_ = true;
        currentMin_ = offset;
        bucketStartMillis_ = arrivalMillis;
    } else if (offset < currentMin_) {
        currentMin_ = offset;
    }
    int64_t baseline = currentMin_;
    if (havePrevious_ && previousMin_ < baseline) baseline = previousMin_;
    const int64_t raw = offset - baseline;
    const int delay = raw > 0 ? int(raw) : 0;
    hold(delay, arrivalMillis);
    return delay;
}

void JitterEstimator::observeRelativeDelay(int delayMillis, int64_t arrivalMillis) {
    hold(delayMillis, arrivalMillis);
}

void JitterEstimator::hold(int delayMillis, int64_t arrivalMillis) {
    if (!havePeak_) {
        havePeak_ = true;
        peakStartMillis_ = arrivalMillis;
        peakMillis_ = 0;
    }
    if (delayMillis > peakMillis_) peakMillis_ = delayMillis;
    if (arrivalMillis - peakStartMillis_ < kPeakHoldMillis) return;
    update(peakMillis_);
    peakMillis_ = 0;
    peakStartMillis_ = arrivalMillis;
}

void JitterEstimator::update(int delayMillis) {
    int index = delayMillis / kTargetBucketMillis;
    if (index >= kTargetBuckets) index = kTargetBuckets - 1;
    // The forget factor ramps in from zero rather than starting at its steady-state value: an
    // empty histogram averaged against is an average with a phantom sample in it, and a cold
    // speaker would follow its own arrivals far too slowly. Reaches kForgetFactorQ15 after ~120
    // updates, which at kPeakHoldMillis is about a minute of speech.
    int64_t forget = (int64_t(1) << 15) - ((int64_t(kStartForgetWeight) << 15) / (updates_ + 1));
    if (forget < 0) forget = 0;
    if (forget > kForgetFactorQ15) forget = kForgetFactorQ15;
    int64_t sum = 0;
    for (int i = 0; i < kTargetBuckets; i++) {
        buckets_[i] = int32_t((int64_t(buckets_[i]) * forget) >> 15);
        sum += buckets_[i];
    }
    buckets_[index] += int32_t((int64_t(1) << 30) - sum);
    updates_++;
}

int JitterEstimator::targetSamples() const {
    int millis = kColdStartMillis;
    if (updates_ > 0) {
        const int64_t want =
            ((int64_t(1) << 30) * kTargetQuantileNumerator) / kTargetQuantileDenominator;
        int64_t sum = 0;
        int index = kTargetBuckets - 1;
        for (int i = 0; i < kTargetBuckets; i++) {
            sum += buckets_[i];
            if (sum >= want) {
                index = i;
                break;
            }
        }
        millis = (1 + index) * kTargetBucketMillis + kSafetyMarginMillis;
    }
    if (millis < kMinTargetMillis) millis = kMinTargetMillis;
    if (millis > kMaxTargetMillis) millis = kMaxTargetMillis;
    return millis * kSamplesPerMilli;
}

void JitterEstimator::seedFrom(const JitterEstimator& other) {
    for (int i = 0; i < kTargetBuckets; i++) buckets_[i] = other.buckets_[i];
    updates_ = other.updates_;
}

void JitterEstimator::reset() {
    *this = JitterEstimator();
}

}  // namespace dumble::playout
