#pragma once
#include <gtest/gtest.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>
#include "WavFixture.h"
#include "core/CaptureConstants.h"

// Scoring for the VAD evaluation corpus: real recorded speech with human-drawn labels. Ground
// truth is the .manual.txt annotation, NOT an energy heuristic, so when the gate disagrees with a
// label the gate is what is wrong. See the corpus README for provenance and licensing. Reading
// lives in WavFixture.h, shared with the parity test.
namespace dumble::eval {

using dumble::fixture::Region;

struct Clip {
    std::string name;
    std::vector<int16_t> pcm;      // 48 kHz mono
    std::vector<Region> speech;    // ascending, non-overlapping
};

inline Clip loadClip(const std::string& name) {
    return {name,
            dumble::fixture::readWav(dumble::fixture::corpusPath(name + ".wav")),
            dumble::fixture::readLabels(dumble::fixture::corpusPath(name + ".manual.txt"))};
}

struct Metrics {
    double coverage = 0;            // fraction of labelled speech frames transmitted
    int worstOnsetMs = 0;           // latest first transmitted frame, among regions we detected
    int worstDropoutMs = 0;         // longest non-transmitting run strictly inside a region that
                                    // transmission later resumes from
    int worstTailMs = 0;            // longest non-transmitting run that instead reaches the
                                    // region's end — tail lag, not dropout: transmission never
                                    // resumed, so there was nothing to drop out of
    // Gate opens in silence not adjacent to any region, per 10s of frames that could produce one:
    // a frame within one hangover of a region (nearSpeech) can never register a false opening, so
    // it does not belong in the denominator. Those exempt frames are 9.7-13% of each clip, so a
    // whole-clip denominator would read ~8-10x lower than the rate at which false openings
    // actually occur in the silence exposed to them.
    double falseOpeningsPerExposed10s = 0;
    int missedRegions = 0;          // regions never transmitted at all — counted, never folded
                                    // into worstOnsetMs, where a short region's total miss would
                                    // read as an ordinary delay and hide behind a longer region's.
                                    // Read worstOnsetMs only alongside this: with every region
                                    // missed, worstOnsetMs is never assigned and reads 0.
};

/**
 * What the gate did, in two views. `transmit` is what reaches the wire, preroll burst included;
 * `openEdge` marks the frames where the gate itself opened.
 *
 * They must stay separate for scoring. Coverage is about what was transmitted, so it reads
 * `transmit`. A false opening is a gate event, so it reads `openEdge` — counting it on `transmit`
 * lets the burst's 60 ms back-fill merge a genuine spurious trigger into the adjacent exempt run
 * and erase its rising edge entirely. Measured: a trigger 250 ms after a region ends, correctly
 * flagged without preroll, became invisible with it.
 */
struct Trace {
    std::vector<bool> transmit;
    std::vector<bool> openEdge;
};

/**
 * Scores a per-frame transmit trace against the labels.
 *
 * Pauses BETWEEN labelled regions are exempt from both coverage and false activation: the
 * hangover is allowed to bridge them, and doing so is correct behaviour rather than a defect.
 * Only an opening in silence that touches no region counts against us.
 */
inline Metrics score(const Clip& clip, const Trace& trace) {
    constexpr int kFrameMs = 10;
    auto frameOf = [](int ms) { return ms / kFrameMs; };
    const int frames = int(trace.transmit.size());

    std::vector<bool> isSpeech(frames, false), nearSpeech(frames, false);
    for (const Region& r : clip.speech) {
        for (int f = std::max(0, frameOf(r.startMs)); f < std::min(frames, frameOf(r.endMs)); f++)
            isSpeech[f] = true;
        // A pause or lead-in within one hangover of a region is not a false opening.
        for (int f = std::max(0, frameOf(r.startMs) - kHangoverFrames);
             f < std::min(frames, frameOf(r.endMs) + kHangoverFrames); f++)
            nearSpeech[f] = true;
    }

    Metrics m;
    int speechFrames = 0, covered = 0, falseOpenings = 0, exposedFrames = 0;
    for (int f = 0; f < frames; f++) {
        if (isSpeech[f]) {
            speechFrames++;
            if (trace.transmit[f]) covered++;
        }
        // isSpeech implies nearSpeech (the region's own frames are inside its exempt window too),
        // so this and the branch above are mutually exclusive despite both being plain `if`s.
        if (!nearSpeech[f]) {
            exposedFrames++;
            if (trace.openEdge[f]) falseOpenings++;
        }
    }
    m.coverage = speechFrames == 0 ? 1.0 : double(covered) / double(speechFrames);
    m.falseOpeningsPerExposed10s = exposedFrames == 0
        ? 0.0
        : double(falseOpenings) * (10000.0 / (double(exposedFrames) * kFrameMs));

    for (const Region& r : clip.speech) {
        const int first = std::max(0, frameOf(r.startMs));
        const int last = std::min(frames, frameOf(r.endMs));
        if (first >= last) continue;   // label outside the audio; nothing to score, not a miss

        int onset = -1;
        for (int f = first; f < last; f++)
            if (trace.transmit[f]) { onset = f - first; break; }
        if (onset < 0) {
            // Never transmitted. Counted on its own rather than folded into worstOnsetMs as the
            // region's length, where a short region's total miss reads as ordinary lag.
            m.missedRegions++;
            continue;
        }
        m.worstOnsetMs = std::max(m.worstOnsetMs, onset * kFrameMs);

        // Scanning from the onset rather than from `first` is what keeps onset lag out of dropout.
        // A gap only counts as dropout if transmission resumes after it; the final gap, which
        // reaches `last` without resuming, is tail lag and would overstate dropout folded in.
        int run = 0;
        for (int f = first + onset; f < last; f++) {
            if (!trace.transmit[f]) { run++; continue; }
            if (run > 0) m.worstDropoutMs = std::max(m.worstDropoutMs, run * kFrameMs);
            run = 0;
        }
        if (run > 0) m.worstTailMs = std::max(m.worstTailMs, run * kFrameMs);
    }
    return m;
}

}  // namespace dumble::eval
