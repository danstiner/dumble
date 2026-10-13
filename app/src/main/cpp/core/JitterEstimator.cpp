#include "core/JitterEstimator.h"
#include <algorithm>

namespace dumble::playout {

using std::chrono::milliseconds;

std::optional<milliseconds> JitterEstimator::observe(uint64_t frameNumber, int samples,
                                                     BootClock::time_point arrival) {
    if (frameNumber > kMaxFrameNumber) {
        // Peer-controlled, and the multiplication below is where an absurd value would stop
        // meaning anything. Dropping the anchor makes the next packet a rebase.
        haveFrame_ = false;
        sawDiscontinuity_ = true;
        return std::nullopt;
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
    nextFrameNumber_ = frameNumber + uint64_t(samples / samplesIn(kFrameNumberUnit));

    const milliseconds offset =
        arrival.time_since_epoch() - int64_t(frameNumber) * kFrameNumberUnit;
    const bool stale = arrival - bucketStart_ >= 2 * kBaselineBucket;
    if (!contiguous || !haveCurrent_ || stale) {
        currentMin_ = offset;
        haveCurrent_ = true;
        havePrevious_ = false;
        bucketStart_ = arrival;
        return std::nullopt;
    }
    if (arrival - bucketStart_ >= kBaselineBucket) {
        previousMin_ = currentMin_;
        havePrevious_ = true;
        currentMin_ = offset;
        bucketStart_ = arrival;
    } else if (offset < currentMin_) {
        currentMin_ = offset;
    }
    milliseconds baseline = currentMin_;
    if (havePrevious_ && previousMin_ < baseline) baseline = previousMin_;
    const milliseconds delay = std::max(offset - baseline, milliseconds::zero());
    hold(delay, arrival);
    return delay;
}

void JitterEstimator::observeRelativeDelay(milliseconds delay, BootClock::time_point arrival) {
    hold(delay, arrival);
}

void JitterEstimator::hold(milliseconds delay, BootClock::time_point arrival) {
    if (!havePeak_) {
        havePeak_ = true;
        peakStart_ = arrival;
        peak_ = milliseconds::zero();
    }
    if (delay > peak_) peak_ = delay;
    if (arrival - peakStart_ < kPeakHold) return;
    update(peak_);
    peak_ = milliseconds::zero();
    peakStart_ = arrival;
}

void JitterEstimator::update(milliseconds delay) {
    const int index = int(std::min<int64_t>(delay / kTargetBucket, kTargetBuckets - 1));
    // The forget factor ramps in from zero rather than starting at its steady-state value: an
    // empty histogram averaged against is an average with a phantom sample in it, and a cold
    // speaker would follow its own arrivals far too slowly. Reaches kForgetFactorQ15 after ~120
    // updates, which at kPeakHold is about a minute of speech.
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
    milliseconds target = kColdStart;
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
        target = (1 + index) * kTargetBucket + kSafetyMargin;
    }
    return samplesIn(std::clamp(target, kMinTarget, kMaxTarget));
}

void JitterEstimator::seedFrom(const JitterEstimator& other) {
    for (int i = 0; i < kTargetBuckets; i++) buckets_[i] = other.buckets_[i];
    updates_ = other.updates_;
}

void JitterEstimator::reset() {
    *this = JitterEstimator();
}

}  // namespace dumble::playout
