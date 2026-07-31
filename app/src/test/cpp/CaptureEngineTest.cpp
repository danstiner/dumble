#include <gtest/gtest.h>
#include <opus.h>
#include <vector>
#include "core/CaptureConstants.h"
#include "core/CaptureEngine.h"

using dumble::CaptureEngine;

namespace {
std::vector<int16_t> tone(int n) {
    std::vector<int16_t> v(n);
    for (int i = 0; i < n; i++) v[i] = int16_t(3000 * ((i / 20) % 2 ? 1 : -1));
    return v;
}
}  // namespace

TEST(CaptureEngine, FrameNumberAdvancesByTwoPerTwentyMillisecondPacket) {
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    e->setGateOpen(true);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxFrameSamples);
    for (int packet = 0; packet < 3; packet++) {
        e->onPcm(pcm.data(), pcm.size());
        ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);
        EXPECT_EQ(uint64_t(packet * 2), fn);
    }
}

TEST(CaptureEngine, FrameNumberSurvivesAGateCycle) {
    // The Mumble client never resets iFrameCounter on key-up or key-down; resetting would hand
    // a receiving client's Speex jitter buffer a backward timestamp jump on every re-press.
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxFrameSamples);

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(0u, fn);
    e->setGateOpen(false);
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);  // terminator
    EXPECT_EQ(2u, fn);

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(4u, fn) << "frame_number restarted across a PTT cycle";
}

TEST(CaptureEngine, ClosingTheGateEmitsExactlyOneTerminator) {
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    e->setGateOpen(true);
    auto pcm = tone(dumble::kTxFrameSamples);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(0u, flags & dumble::kFlagTerminator);

    e->setGateOpen(false);
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(dumble::kFlagTerminator, flags & dumble::kFlagTerminator);

    // The gate is closed and the terminator is spent: nothing more is produced.
    e->setWaitMillisForTest(1);
    EXPECT_EQ(0, e->pollFrame(out.data(), int(out.size()), &fn, &flags));
}

TEST(CaptureEngine, ASpurtShorterThanOneFrameStillTerminates) {
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    e->setGateOpen(true);
    auto pcm = tone(100);
    e->onPcm(pcm.data(), pcm.size());
    e->setGateOpen(false);
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(dumble::kFlagTerminator, flags & dumble::kFlagTerminator);
}

TEST(CaptureEngine, AudioDeliveredBeforeThePressIsNeverSentAsPreRoll) {
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxFrameSamples * 3);
    e->onPcm(pcm.data(), pcm.size());          // delivered while the gate was closed
    e->setGateOpen(true);
    e->setWaitMillisForTest(1);
    EXPECT_EQ(0, e->pollFrame(out.data(), int(out.size()), &fn, &flags))
        << "pre-press audio was transmitted";
}

TEST(CaptureEngine, ShutdownAndStreamDownAreDistinguishable) {
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    e->setWaitMillisForTest(1);

    e->setStreamDown(true);
    EXPECT_EQ(dumble::kPollRetry, e->pollFrame(out.data(), int(out.size()), &fn, &flags));

    e->requestShutdown();
    EXPECT_EQ(dumble::kPollShutdown, e->pollFrame(out.data(), int(out.size()), &fn, &flags));
}

TEST(CaptureEngine, ACloseThenReopenWithinOnePollIntervalMergesTheSpurts) {
    // A close immediately followed by a reopen -- button debounce, or a fast re-press -- before
    // the pump ever polls the close: the owed terminator is cancelled and both presses continue
    // as one transmission. Desktop Mumble's frame-granular gate produces exactly this for a
    // release-and-press inside one frame -- continuous audio, no stream restart -- and a
    // terminator boundary here would need the close-position bookkeeping this design deleted.
    // The terminator arrives once, at the true end.
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxFrameSamples);

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);  // press 1's audio
    EXPECT_EQ(0u, flags & dumble::kFlagTerminator);

    e->setGateOpen(false);
    e->setGateOpen(true);              // reopened before the pump ever polled the close
    e->onPcm(pcm.data(), pcm.size());  // press 2's audio joins the same transmission

    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(0u, flags & dumble::kFlagTerminator)
        << "the merged transmission was interrupted by a terminator";

    e->setGateOpen(false);
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(dumble::kFlagTerminator, flags & dumble::kFlagTerminator)
        << "the merged transmission never terminated";
}

TEST(CaptureEngine, DoubleCloseWithNoInterveningPollNeverEncodesGateClosedAudio) {
    // Two full gate cycles -- open, onPcm, close, onPcm, open, onPcm, close -- with zero polls
    // until after both closes: the fault mode a stalled pump can hit (kHighWaterSamples' own
    // comment already treats this as first-class). The reopen merges the cycles into one
    // transmission, so both spurts' audio rides a single terminator; what must survive any of
    // that is the invariant this test exists for. Audio delivered while PTT is up must never
    // reach the wire, unconditionally, regardless of gate-cycle count or poll timing -- a
    // privacy violation, not a glitch. The gate in onPcm() enforces it at the source: gate-closed
    // audio is never captured at all.
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    e->setWaitMillisForTest(1);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;

    auto spurtTone = tone(100);
    // Same waveform shape as tone(), just far louder, so a leak is unmistakable in the decode.
    std::vector<int16_t> closedGateAudio(100);
    for (int i = 0; i < 100; i++) closedGateAudio[i] = int16_t(20000 * ((i / 20) % 2 ? 1 : -1));

    e->setGateOpen(true);
    e->onPcm(spurtTone.data(), spurtTone.size());               // spurt 1
    e->setGateOpen(false);
    e->onPcm(closedGateAudio.data(), closedGateAudio.size());   // gate closed: must never be sent
    e->setGateOpen(true);
    e->onPcm(spurtTone.data(), spurtTone.size());               // spurt 2
    e->setGateOpen(false);

    int err = OPUS_OK;
    OpusDecoder* dec = opus_decoder_create(dumble::kSampleRate, dumble::kChannels, &err);
    ASSERT_EQ(OPUS_OK, err);

    std::vector<int16_t> pcm(dumble::kTxFrameSamples);
    int packets = 0;
    for (int i = 0; i < 4; i++) {
        const int bytes = e->pollFrame(out.data(), int(out.size()), &fn, &flags);
        if (bytes <= 0) break;
        packets++;
        const int samples =
            opus_decode(dec, out.data(), bytes, pcm.data(), dumble::kTxFrameSamples, 0);
        ASSERT_GT(samples, 0);
        for (int16_t s : pcm) {
            ASSERT_TRUE(s > -10000 && s < 10000)
                << "decoded terminator packet contains gate-closed audio";
        }
    }
    opus_decoder_destroy(dec);
    EXPECT_GT(packets, 0);
}

TEST(CaptureEngine, FrameNumberReflectsElapsedTimeAcrossAClosedGateGap) {
    // The Mumble client advances iFrameCounter every 10ms of microphone audio unconditionally,
    // speech or not -- not only when a packet is emitted. A counter that freezes while the gate
    // is closed hands a resumed talkspurt a frame_number implying no time passed, which schedules
    // it in the past of a still-alive receive-side jitter buffer (retires after ~100ms) and gets
    // it dropped as late: the opening audio of a quick re-press silently disappears at the far end.
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxFrameSamples);

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);
    e->setGateOpen(false);
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);  // terminator
    const uint64_t terminatorFn = fn;

    // A full second elapses while the gate is closed -- the way a live mic keeps delivering
    // through any pause between talk spurts. None of it is captured (the gate in onPcm() writes
    // nothing to the ring), yet the clock must count all of it.
    auto closedGateAudio = tone(dumble::kSampleRate);
    e->onPcm(closedGateAudio.data(), closedGateAudio.size());

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);

    // One second is 100 units of the 10ms frame_number clock. A counter that only advances on
    // emitted packets shows a gap of exactly frameNumberStep_ (2) here instead.
    EXPECT_GE(fn, terminatorFn + 90)
        << "frame_number did not track the wall-clock gap while the gate was closed";
}

TEST(CaptureEngine, FrameNumberIsStrictlyIncreasingAcrossSpurtsAndTerminators) {
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxFrameSamples);
    auto shortPcm = tone(100);

    std::vector<uint64_t> seen;
    auto poll = [&]() {
        ASSERT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0);
        seen.push_back(fn);
    };

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    poll();                                      // spurt 1 audio

    e->setGateOpen(false);
    poll();                                      // spurt 1 terminator

    e->setGateOpen(true);                         // near-instant re-press, no closed-gate gap
    e->onPcm(shortPcm.data(), shortPcm.size());    // shorter than one frame
    e->setGateOpen(false);
    poll();                                       // spurt 2 terminator (short-spurt flush)

    // A substantial pause, then a third spurt.
    auto closedGateAudio = tone(dumble::kSampleRate);
    e->onPcm(closedGateAudio.data(), closedGateAudio.size());
    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    poll();                                       // spurt 3 audio
    e->setGateOpen(false);
    poll();                                       // spurt 3 terminator

    ASSERT_EQ(5u, seen.size());
    for (size_t i = 1; i < seen.size(); i++) {
        EXPECT_GT(seen[i], seen[i - 1]) << "frame_number did not strictly increase at index " << i;
    }
    // The pause before spurt 3 must show up as a bigger-than-minimum jump somewhere in the
    // sequence: a counter that only advances on emitted packets satisfies strict monotonicity
    // trivially (every step is exactly frameNumberStep_) while masking exactly the bug this round
    // fixes.
    const uint64_t step = dumble::kTxFrameSamples / dumble::kFrameNumberUnitSamples;
    bool sawWallClockJump = false;
    for (size_t i = 1; i < seen.size(); i++) {
        if (seen[i] - seen[i - 1] > step) sawWallClockJump = true;
    }
    EXPECT_TRUE(sawWallClockJump) << "no gap in the sequence reflected the closed-gate pause";
}

// A repeat of the state we are already in is not a transition and must do nothing. Nothing above
// enforces alternation: VoiceSender.setTransmitting is a pass-through, and a release plausibly
// arrives twice (a pointer-up alongside a lifecycle-driven release). With the gate at onPcm() a
// repeated close can no longer sweep closed-gate audio into a flush — nothing closed-gate is
// buffered — but unguarded it would owe a second, empty terminator after the real one was spent.
// Same privacy assertion as the double-close test, one gate cycle short, kept as regression proof.
TEST(CaptureEngine, ARedundantCloseNeverFlushesGateClosedAudio) {
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    e->setWaitMillisForTest(1);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;

    auto spurtTone = tone(dumble::kTxFrameSamples);
    // The same waveform far louder, so a leak is unmistakable in the decode.
    std::vector<int16_t> closedGateAudio(500);
    for (int i = 0; i < 500; i++) closedGateAudio[i] = int16_t(20000 * ((i / 20) % 2 ? 1 : -1));

    e->setGateOpen(true);
    e->onPcm(spurtTone.data(), spurtTone.size());
    e->setGateOpen(false);
    e->onPcm(closedGateAudio.data(), closedGateAudio.size());   // gate closed: must never be sent
    e->setGateOpen(false);                                       // redundant release

    int err = OPUS_OK;
    OpusDecoder* dec = opus_decoder_create(dumble::kSampleRate, dumble::kChannels, &err);
    ASSERT_EQ(OPUS_OK, err);
    std::vector<int16_t> pcm(dumble::kTxFrameSamples);
    for (int i = 0; i < 4; i++) {
        const int bytes = e->pollFrame(out.data(), int(out.size()), &fn, &flags);
        if (bytes <= 0) break;
        const int samples =
            opus_decode(dec, out.data(), bytes, pcm.data(), dumble::kTxFrameSamples, 0);
        ASSERT_GT(samples, 0);
        for (int16_t s : pcm) {
            ASSERT_TRUE(s > -10000 && s < 10000)
                << "a redundant close flushed gate-closed audio";
        }
    }
    opus_decoder_destroy(dec);
}

// The open-side twin. Unguarded, a repeated press would re-anchor the live spurt to now: the
// audio already buffered then sits before the anchor, and the frame-number delta (readPos minus
// openIdx) wraps enormous, stamping the next packet with a garbage frame number. Milder than a
// privacy breach and fixed by the same guard.
TEST(CaptureEngine, ARedundantOpenDoesNotDisturbTheSpurtAlreadyInFlight) {
    auto e = CaptureEngine::create(dumble::kSampleRate, dumble::kTxFrameSamples, 40000);
    ASSERT_TRUE(e);
    e->setWaitMillisForTest(1);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;

    e->setGateOpen(true);
    auto pcm = tone(dumble::kTxFrameSamples);
    e->onPcm(pcm.data(), pcm.size());   // a full frame, captured and owed to the wire
    e->setGateOpen(true);               // redundant press before the pump ever polled

    EXPECT_GT(e->pollFrame(out.data(), int(out.size()), &fn, &flags), 0)
        << "a redundant open discarded audio already captured for this spurt";
    EXPECT_EQ(0u, fn) << "a redundant open re-anchored the spurt and corrupted its frame number";
}

// The engine has no meaningful state without an encoder, so a failed encoder must fail the whole
// construction — not leave a live engine whose every poll silently drops the frame it just built.
TEST(CaptureEngine, CreateFailsWhenTheEncoderCannotBeBuilt) {
    EXPECT_EQ(nullptr, CaptureEngine::create(44100, dumble::kTxFrameSamples, 40000));
}
