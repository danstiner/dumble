#include <gtest/gtest.h>
#include <opus.h>
#include <vector>
#include "core/CaptureConstants.h"
#include "core/CaptureEngine.h"
#include "WavFixture.h"

using dumble::CaptureEngine;

namespace {

constexpr int kBurstPackets = dumble::kPrerollFrames / dumble::kFramesPerPacket;
static_assert(kBurstPackets * dumble::kFramesPerPacket == dumble::kPrerollFrames,
              "kPrerollFrames must be a multiple of kFramesPerPacket");

std::vector<int16_t> tone(int n) {
    std::vector<int16_t> v(n);
    for (int i = 0; i < n; i++) v[i] = int16_t(3000 * ((i / 20) % 2 ? 1 : -1));
    return v;
}

// Blocks until pollPacket returns a packet (data or terminator), returning its payload. Every
// packet in these tests is already fully buffered before the first call, so in practice this
// never loops more than once.
std::vector<uint8_t> pollUntilPacket(CaptureEngine& e) {
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    int bytes = 0;
    while ((bytes = e.pollPacket(out.data(), int(out.size()), &fn, &flags)) <= 0) {}
    return std::vector<uint8_t>(out.begin(), out.begin() + bytes);
}

// Opens the gate, sends one full packet of tone, polls out the resulting packet, then closes the
// gate and drains the terminator so the engine is settled for whatever the caller does next.
std::vector<uint8_t> firstPacketOfASpurt(CaptureEngine& e) {
    auto pcm = tone(dumble::kTxPacketSamples);
    e.setGateOpen(true);
    e.onPcm(pcm.data(), pcm.size());
    const auto packet = pollUntilPacket(e);
    e.setGateOpen(false);
    pollUntilPacket(e);  // drain the terminator
    return packet;
}

std::unique_ptr<CaptureEngine> engine(int bitrate = 40000) {
    const auto& blob = dumble::fixture::weightBlob();
    return CaptureEngine::create(bitrate, blob.data(), blob.size());
}

// Polls until the engine produces nothing, discarding whatever comes out.
void drain(CaptureEngine& e) {
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    while (e.pollPacket(out, sizeof(out), &fn, &flags) > 0) {}
}
}  // namespace

TEST(CaptureEngine, FrameNumberAdvancesByTwoPerTwentyMillisecondPacket) {
    auto e = engine();
    ASSERT_TRUE(e);
    e->setGateOpen(true);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxPacketSamples);
    for (int packet = 0; packet < 3; packet++) {
        e->onPcm(pcm.data(), pcm.size());
        ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
        EXPECT_EQ(uint64_t(packet * 2), fn);
    }
}

TEST(CaptureEngine, FrameNumberSurvivesAGateCycle) {
    // The Mumble client never resets iFrameCounter on key-up or key-down; resetting would hand
    // a receiving client's Speex jitter buffer a backward timestamp jump on every re-press.
    auto e = engine();
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxPacketSamples);

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(0u, fn);
    e->setGateOpen(false);
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);  // terminator
    EXPECT_EQ(2u, fn);

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(4u, fn) << "frame_number restarted across a PTT cycle";
}

TEST(CaptureEngine, ClosingTheGateEmitsExactlyOneTerminator) {
    auto e = engine();
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    e->setGateOpen(true);
    auto pcm = tone(dumble::kTxPacketSamples);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(0u, flags & dumble::kFlagTerminator);

    e->setGateOpen(false);
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(dumble::kFlagTerminator, flags & dumble::kFlagTerminator);

    // The gate is closed and the terminator is spent: nothing more is produced.
    e->setWaitMillisForTest(1);
    EXPECT_EQ(0, e->pollPacket(out.data(), int(out.size()), &fn, &flags));
}

TEST(CaptureEngine, ASpurtShorterThanOneFrameStillTerminates) {
    auto e = engine();
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    e->setGateOpen(true);
    auto pcm = tone(100);
    e->onPcm(pcm.data(), pcm.size());
    e->setGateOpen(false);
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(dumble::kFlagTerminator, flags & dumble::kFlagTerminator);
}

TEST(CaptureEngine, AudioDeliveredBeforeThePressIsNeverSentAsPreRoll) {
    auto e = engine();
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxPacketSamples * 3);
    e->onPcm(pcm.data(), pcm.size());          // delivered while the gate was closed
    e->setGateOpen(true);
    e->setWaitMillisForTest(1);
    EXPECT_EQ(0, e->pollPacket(out.data(), int(out.size()), &fn, &flags))
        << "pre-press audio was transmitted";
}

TEST(CaptureEngine, ShutdownAndStreamDownAreDistinguishable) {
    auto e = engine();
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    e->setWaitMillisForTest(1);

    e->setStreamDown(true);
    EXPECT_EQ(dumble::kPollRetry, e->pollPacket(out.data(), int(out.size()), &fn, &flags));

    e->requestShutdown();
    EXPECT_EQ(dumble::kPollShutdown, e->pollPacket(out.data(), int(out.size()), &fn, &flags));
}

TEST(CaptureEngine, StreamUnavailableIsDistinctFromRetryAndOutranksIt) {
    // OboeCapture sets both streamDown and streamUnavailable when it gives up reopening (the
    // former was already true from the disconnect that started the retry sequence). A caller
    // must see kPollUnavailable, not kPollRetry, or it would poll forever for a stream that is
    // never coming back.
    auto e = engine();
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    e->setWaitMillisForTest(1);

    e->setStreamDown(true);
    EXPECT_EQ(dumble::kPollRetry, e->pollPacket(out.data(), int(out.size()), &fn, &flags));

    e->setStreamUnavailable();
    EXPECT_EQ(dumble::kPollUnavailable, e->pollPacket(out.data(), int(out.size()), &fn, &flags));
    // Not a one-shot: every subsequent poll must keep reporting it, since there is no path back.
    EXPECT_EQ(dumble::kPollUnavailable, e->pollPacket(out.data(), int(out.size()), &fn, &flags));
}

TEST(CaptureEngine, ShutdownOutranksStreamUnavailable) {
    // requestShutdown() (an explicit stop()) must win even if the stream had already been
    // declared unrecoverable — kPollShutdown, not kPollUnavailable, is what tells the pump loop
    // to exit rather than surface a "transmit unavailable" state after the user hung up anyway.
    auto e = engine();
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    e->setWaitMillisForTest(1);

    e->setStreamUnavailable();
    e->requestShutdown();
    EXPECT_EQ(dumble::kPollShutdown, e->pollPacket(out.data(), int(out.size()), &fn, &flags));
}

TEST(CaptureEngine, ACloseThenReopenWithinOnePollIntervalMergesTheSpurts) {
    // A close immediately followed by a reopen -- button debounce, or a fast re-press -- before
    // the pump ever polls the close: the owed terminator is cancelled and both presses continue
    // as one transmission. Desktop Mumble's frame-granular gate produces exactly this for a
    // release-and-press inside one frame -- continuous audio, no stream restart -- and a
    // terminator boundary here would need the close-position bookkeeping this design deleted.
    // The terminator arrives once, at the true end.
    auto e = engine();
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxPacketSamples);

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);  // press 1's audio
    EXPECT_EQ(0u, flags & dumble::kFlagTerminator);

    e->setGateOpen(false);
    e->setGateOpen(true);              // reopened before the pump ever polled the close
    e->onPcm(pcm.data(), pcm.size());  // press 2's audio joins the same transmission

    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
    EXPECT_EQ(0u, flags & dumble::kFlagTerminator)
        << "the merged transmission was interrupted by a terminator";

    e->setGateOpen(false);
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
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
    auto e = engine();
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

    std::vector<int16_t> pcm(dumble::kTxPacketSamples);
    int packets = 0;
    for (int i = 0; i < 4; i++) {
        const int bytes = e->pollPacket(out.data(), int(out.size()), &fn, &flags);
        if (bytes <= 0) break;
        packets++;
        const int samples =
            opus_decode(dec, out.data(), bytes, pcm.data(), dumble::kTxPacketSamples, 0);
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
    auto e = engine();
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxPacketSamples);

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
    e->setGateOpen(false);
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);  // terminator
    const uint64_t terminatorFn = fn;

    // A full second elapses while the gate is closed -- the way a live mic keeps delivering
    // through any pause between talk spurts. None of it is captured (the gate in onPcm() writes
    // nothing to the ring), yet the clock must count all of it.
    auto closedGateAudio = tone(dumble::kSampleRate);
    e->onPcm(closedGateAudio.data(), closedGateAudio.size());

    e->setGateOpen(true);
    e->onPcm(pcm.data(), pcm.size());
    ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);

    // One second is 100 units of the 10ms frame_number clock. A counter that only advances on
    // emitted packets shows a gap of exactly frameNumberStep_ (2) here instead.
    EXPECT_GE(fn, terminatorFn + 90)
        << "frame_number did not track the wall-clock gap while the gate was closed";
}

TEST(CaptureEngine, FrameNumberIsStrictlyIncreasingAcrossSpurtsAndTerminators) {
    auto e = engine();
    ASSERT_TRUE(e);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxPacketSamples);
    auto shortPcm = tone(100);

    std::vector<uint64_t> seen;
    auto poll = [&]() {
        ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
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
    const uint64_t step = dumble::kTxPacketSamples / dumble::kFrameSamples;
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
    auto e = engine();
    ASSERT_TRUE(e);
    e->setWaitMillisForTest(1);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;

    auto spurtTone = tone(dumble::kTxPacketSamples);
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
    std::vector<int16_t> pcm(dumble::kTxPacketSamples);
    for (int i = 0; i < 4; i++) {
        const int bytes = e->pollPacket(out.data(), int(out.size()), &fn, &flags);
        if (bytes <= 0) break;
        const int samples =
            opus_decode(dec, out.data(), bytes, pcm.data(), dumble::kTxPacketSamples, 0);
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
    auto e = engine();
    ASSERT_TRUE(e);
    e->setWaitMillisForTest(1);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;

    e->setGateOpen(true);
    auto pcm = tone(dumble::kTxPacketSamples);
    e->onPcm(pcm.data(), pcm.size());   // a full frame, captured and owed to the wire
    e->setGateOpen(true);               // redundant press before the pump ever polled

    EXPECT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0)
        << "a redundant open discarded audio already captured for this spurt";
    EXPECT_EQ(0u, fn) << "a redundant open re-anchored the spurt and corrupted its frame number";
}

TEST(CaptureEngine, ResetsTheEncoderAtASpurtOnsetButNotOnAMerge) {
    auto e = engine();
    ASSERT_TRUE(e);
    // Two spurts of identical audio, separated by a settled close. With a spurt-onset reset the
    // second spurt's first packet must be byte-identical to the first spurt's first packet.
    const auto first = firstPacketOfASpurt(*e);
    const auto second = firstPacketOfASpurt(*e);
    EXPECT_EQ(first, second) << "the second spurt did not start from a cold encoder";
}

TEST(CaptureEngine, AMergeKeepsEncoderStateThatAGenuineOnsetWouldReset) {
    // Both engines run an identical warm-up -- one data packet, then a close that owes a
    // terminator -- so their encoders hold byte-identical predictor state entering the branch
    // below. From there they diverge only in how the next spurt opens.
    auto pcm = tone(dumble::kTxPacketSamples);

    auto merged = engine();
    ASSERT_TRUE(merged);
    merged->setGateOpen(true);
    merged->onPcm(pcm.data(), pcm.size());
    pollUntilPacket(*merged);
    merged->setGateOpen(false);

    auto settled = engine();
    ASSERT_TRUE(settled);
    settled->setGateOpen(true);
    settled->onPcm(pcm.data(), pcm.size());
    pollUntilPacket(*settled);
    settled->setGateOpen(false);

    // merged: reopened before the pump ever claimed the close -- a merge, must keep the
    // encoder's predictor state.
    merged->setGateOpen(true);
    merged->onPcm(pcm.data(), pcm.size());
    const auto mergePacket = pollUntilPacket(*merged);

    // settled: the close is claimed first (terminator drained, state settles to Closed) before
    // the reopen -- a genuine onset, must reset.
    pollUntilPacket(*settled);   // terminator
    settled->setGateOpen(true);
    settled->onPcm(pcm.data(), pcm.size());
    const auto onsetPacket = pollUntilPacket(*settled);

    EXPECT_NE(mergePacket, onsetPacket)
        << "the merge reset the encoder the same as a genuine onset would";
}

// The engine has no meaningful state without an encoder, so a failed encoder must fail the whole
// construction — not leave a live engine whose every poll silently drops the frame it just built.
TEST(CaptureEngine, CreateFailsWhenTheEncoderCannotBeBuilt) {
    EXPECT_EQ(nullptr, engine(/* bitrate = */ 0));
}

TEST(CaptureEngine, RecordsEncodeTiming) {
    auto e = engine();
    ASSERT_TRUE(e);
    e->setGateOpen(true);
    std::vector<uint8_t> out(4000);
    uint64_t fn = 0; uint32_t flags = 0;
    auto pcm = tone(dumble::kTxPacketSamples);
    for (int i = 0; i < 10; i++) {
        e->onPcm(pcm.data(), pcm.size());
        ASSERT_GT(e->pollPacket(out.data(), int(out.size()), &fn, &flags), 0);
    }
    // Mean over max is the pair that matters: a mean well under the 20 ms packet budget with a
    // max near it is the signature of an encoder that mostly keeps up and occasionally does not.
    EXPECT_GT(e->encodeMicrosMean(), 0u);
    EXPECT_GE(e->encodeMicrosMax(), e->encodeMicrosMean());
}

TEST(CaptureEngine, EncodeTimingStaysZeroUntilSomethingIsEncoded) {
    auto e = engine();
    ASSERT_TRUE(e);
    // Division by the count is the trap here — a mean read before the first encode must not
    // divide by zero.
    EXPECT_EQ(0u, e->encodeMicrosMean());
    EXPECT_EQ(0u, e->encodeMicrosMax());
}

// A blob that will not load is a broken build. Refused outright: a push-to-talk-only engine would
// leave the app holding the gate open against an engine that never entered voice activity.
TEST(CaptureEngine, RefusesTheEngineWhenWeightsWillNotLoad) {
    std::vector<float> wrong(16, 0.0f);
    EXPECT_EQ(nullptr, CaptureEngine::create(40000,
                                             wrong.data(), wrong.size() * sizeof(float)));
    EXPECT_EQ(nullptr, CaptureEngine::create(40000, nullptr, 0));
}

TEST(CaptureEngine, AcceptsTheShippedBlob) {
    ASSERT_TRUE(engine());
}

TEST(CaptureEngine, VoiceActivityEndsTheSpurtWithATerminatorWhenSpeechStops) {
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);
    const auto pcm = dumble::fixture::readWav(
        dumble::fixture::referencePath("synthetic.wav"));
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    int transmitted = 0;
    uint32_t lastFlags = 0;
    auto feed = [&](const int16_t* p) {
        e->onPcm(p, dumble::kTxPacketSamples);
        if (e->pollPacket(out, sizeof(out), &fn, &flags) > 0) { transmitted++; lastFlags = flags; }
    };
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size(); at += dumble::kTxPacketSamples)
        feed(pcm.data() + at);
    // Silence past the hangover (kHangoverFrames = 200 ms), so the detector closes the spurt on
    // its own — no setGateOpen(false); this is the gate SpeechGate owns, not the arming gate.
    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    for (int i = 0; i < 30; i++) feed(quiet.data());
    ASSERT_GT(transmitted, 0);
    EXPECT_EQ(dumble::kFlagTerminator, lastFlags & dumble::kFlagTerminator)
        << "the spurt's final packet must carry the terminator";
}

TEST(CaptureEngine, VoiceActivityDrainsTheRingWhileSilent) {
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);                    // armed for the session

    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    for (int i = 0; i < 20; i++) {
        e->onPcm(quiet.data(), uint32_t(quiet.size()));
        EXPECT_EQ(0, e->pollPacket(out, sizeof(out), &fn, &flags)) << "silence must not transmit";
    }
    // Nothing transmitted, but the ring must not have grown a backlog: a detector that cannot see
    // the audio cannot judge it, so the drain is the whole point of holding the arming gate open.
    EXPECT_LT(e->bufferedSamples(), uint32_t(dumble::kTxPacketSamples))
        << "the ring is accumulating while silent";
}

TEST(CaptureEngine, VoiceActivityTransmitsSpeech) {
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);
    const auto pcm = dumble::fixture::readWav(
        dumble::fixture::referencePath("synthetic.wav"));
    int transmitted = 0;
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size(); at += dumble::kTxPacketSamples) {
        e->onPcm(pcm.data() + at, dumble::kTxPacketSamples);
        if (e->pollPacket(out, sizeof(out), &fn, &flags) > 0) transmitted++;
    }
    EXPECT_GT(transmitted, 10) << "the detector never opened on real speech";
}

TEST(CaptureEngine, ThePrerollQueueHoldsFramesNotPackets) {
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);

    // Seven silent frames, fed and drained one at a time. Packet-native, seven 10 ms feeds filled
    // at most three history slots; frame-native they fill all seven.
    const std::vector<int16_t> quiet(dumble::kFrameSamples, 0);
    for (int i = 0; i < 7; i++) {
        e->onPcm(quiet.data(), dumble::kFrameSamples);
        drain(*e);
    }
    EXPECT_EQ(dumble::kPrerollFrames + 1, e->heldFramesForTest())
        << "the queue must hold one frame per 10 ms of preroll, not one per packet";
}

TEST(CaptureEngine, AnOpeningEdgeFlushesTheHeldPreroll) {
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);

    // Enough silence to fill the history, then speech.
    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    for (int i = 0; i < 10; i++) { e->onPcm(quiet.data(), dumble::kTxPacketSamples); drain(*e); }

    const auto pcm = dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav"));
    std::vector<uint64_t> frameNumbers;
    // Every packet fed so far, quiet fill included. The pump takes exactly one packet per fed
    // packet, and the gate has been open since sample zero, so packet i's wall-clock candidate is
    // i * kFramesPerPacket — which is what lets the assertions below pin the burst to real time.
    size_t fedPackets = 10;
    size_t fedWhenFirstPacket = 0;
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size() && frameNumbers.size() < 6;
         at += dumble::kTxPacketSamples) {
        e->onPcm(pcm.data() + at, dumble::kTxPacketSamples);
        fedPackets++;
        // One poll per packet is not enough while a burst drains, so poll until it stops producing.
        for (int n = e->pollPacket(out, sizeof(out), &fn, &flags); n > 0;
             n = e->pollPacket(out, sizeof(out), &fn, &flags)) {
            if (frameNumbers.empty()) fedWhenFirstPacket = fedPackets;
            frameNumbers.push_back(fn);
        }
    }
    ASSERT_GE(frameNumbers.size(), size_t(kBurstPackets) + 1)
        << "the opening edge did not flush a burst";
    for (size_t i = 1; i < frameNumbers.size(); i++)
        EXPECT_GT(frameNumbers[i], frameNumbers[i - 1]) << "frame numbers must strictly increase";
    // Monotonicity alone cannot see the burst or its order: the frameNumber_ floor clamps a
    // reversed or absent burst into an increasing sequence anyway. Anchor to the wall clock
    // instead. Frame-native, the opening edge emits in the same poll that detected speech, and the
    // burst starts at the OLDEST held frame: kPrerollFrames frames before the
    // onset frame -- the full held preroll, not one frame short of it. A packet-fed test cannot see
    // which of the fed packet's two frames opened, so the anchor pins the packet the onset frame
    // must fall in; Task 3's eval measures the exact frame.
    //
    // This does NOT reliably catch a queue one slot short (kHistorySlots off by one, back-filling
    // 5 frames instead of 6): the division below truncates, so a frameNumbers[0] shifted by the bug
    // still divides to the same integer whenever the onset frame index is even -- fixture parity,
    // not logic. ThePrerollQueueHoldsFramesNotPackets (:600) is what catches a wrong slot count
    // deterministically: it asserts heldFramesForTest() == 7 outright, with no arithmetic to get
    // lucky through.
    const uint64_t step = dumble::kFramesPerPacket;
    const uint64_t heldFrames = dumble::kPrerollFrames;
    EXPECT_EQ(fedWhenFirstPacket - 1, (frameNumbers[0] + heldFrames) / step)
        << "the burst must back-fill the whole held preroll, not part of it";
    // Consecutive as well: the spurt is continuous speech, so from the burst's oldest frame on,
    // every emitted packet is exactly one step apart. A burst that strands its newest entry --
    // the onset frame -- leaves a double step right after the held ones, which strict
    // increase and the anchor above both wave through.
    for (size_t i = 1; i < frameNumbers.size(); i++)
        EXPECT_EQ(frameNumbers[0] + i * step, frameNumbers[i])
            << "a packet went missing at index " << i;
}

TEST(CaptureEngine, AnOpeningEdgeResetsTheEncoderOncePerBurstNotPerPacket) {
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);

    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    for (int i = 0; i < 10; i++) { e->onPcm(quiet.data(), dumble::kTxPacketSamples); drain(*e); }
    ASSERT_EQ(0u, e->encoderResets()) << "nothing has been encoded, so nothing can have reset";

    const auto pcm = dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav"));
    int packets = 0;
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size() &&
                        packets < kBurstPackets + 3;
         at += dumble::kTxPacketSamples) {
        e->onPcm(pcm.data() + at, dumble::kTxPacketSamples);
        for (int n = e->pollPacket(out, sizeof(out), &fn, &flags); n > 0;
             n = e->pollPacket(out, sizeof(out), &fn, &flags))
            packets++;
    }
    ASSERT_GT(packets, kBurstPackets) << "no burst to measure";
    // The arming press and the opening edge both raise the same pending flag, and the burst's
    // first encode claims it once — a reset before each held packet would count kPrerollPackets.
    EXPECT_EQ(1u, e->encoderResets())
        << "the encoder must go cold once at the opening edge, not once per held packet";
}

TEST(CaptureEngine, AMuteCycleCannotReplayPreMuteAudioIntoTheNextBurst) {
    // The failure this prevents: the user says something the detector judged silent, mutes,
    // unmutes, then speaks. Without the clear, the opening burst transmits up to 60 ms of audio
    // they believed was never sent — with frame numbers the floor clamp launders into valid
    // post-terminator values, so nothing on the wire marks it.
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);

    // A distinctive full-scale tone, judged silent by the gate but trivially detectable in output.
    std::vector<int16_t> marker(dumble::kTxPacketSamples);
    for (size_t i = 0; i < marker.size(); i++)
        marker[i] = int16_t(((i / 8) % 2) ? 20000 : -20000);
    for (int i = 0; i < kBurstPackets; i++) {
        e->onPcm(marker.data(), dumble::kTxPacketSamples);
        drain(*e);
    }

    e->setGateOpen(false);   // mute
    drain(*e);               // let the terminator out
    e->setGateOpen(true);    // unmute

    // Decode nothing — assert on the engine's own state, which is what the clear owns.
    EXPECT_EQ(0, e->heldFramesForTest()) << "pre-mute audio survived the arming transition";
}

TEST(CaptureEngine, AMuteCycleWithNoPollBetweenTransitionsCannotReplayPreMuteAudio) {
    // Regression for a ring-leak found by adversarial review: without a ring reset in the
    // new-spurt branch of setGateOpen(true), a close→open with no intervening poll left
    // pre-mute audio in the ring, and the next session's VA loop read it into preroll.
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);
    e->setWaitMillisForTest(1);

    // Feed audio WITHOUT draining, so the ring holds unconsumed samples.
    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    for (int i = 0; i < kBurstPackets; i++)
        e->onPcm(quiet.data(), dumble::kTxPacketSamples);

    // Close and reopen with NO poll in between — the exact race the ring reset closes.
    e->setGateOpen(false);
    e->setGateOpen(true);

    // The first poll after the reopen clears history (resetDetectorPending_) and then enters
    // the VA loop. Without the ring reset in setGateOpen(true), the loop would read stale
    // pre-mute samples from the ring into the new session's preroll.
    drain(*e);

    EXPECT_EQ(0, e->heldFramesForTest())
        << "pre-mute audio re-entered the preroll queue after a close→open with no poll between";
}

TEST(CaptureEngine, AMuteDoesNotLeaveTheDetectorsHangoverToLeakIntoTheNextSpurt) {
    // The LSTM leak in slower motion: SpeechGate's hangover keeps deciding transmit=true for up to
    // 200 ms after speech stops. Without a detector reset, silence right after an unmute can still
    // ride the pre-mute hangover and open a spurt on its own -- new audio, but the decision to send
    // it was made by state left over from before the mute, not by anything heard since.
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);

    const auto pcm = dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav"));
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    int outputs = 0;
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size() &&
                        outputs < kBurstPackets + 1;
         at += dumble::kTxPacketSamples) {
        e->onPcm(pcm.data() + at, dumble::kTxPacketSamples);
        while (e->pollPacket(out, sizeof(out), &fn, &flags) > 0) outputs++;
    }
    ASSERT_GE(outputs, kBurstPackets + 1) << "the spurt never opened";

    e->setGateOpen(false);   // mute mid-spurt: the detector's hangover is still fresh
    drain(*e);                // let the terminator out
    e->setGateOpen(true);     // unmute

    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    e->onPcm(quiet.data(), dumble::kTxPacketSamples);
    // Frame-native, an opening edge emits in the poll that detects it — a leaked hangover shows
    // up on the FIRST poll, so both must stay empty.
    EXPECT_EQ(0, e->pollPacket(out, sizeof(out), &fn, &flags))
        << "silence right after unmute opened a spurt on the pre-mute detector's leaked hangover";
    EXPECT_EQ(0, e->pollPacket(out, sizeof(out), &fn, &flags))
        << "a leaked-hangover spurt is still draining one poll after the unmute";
}

TEST(CaptureEngine, AModeChangeMidSpurtOwesATerminator) {
    // A mode change lands while a VA spurt is already on the wire; switching away from the
    // detector must not orphan that spurt's receiver -- it gets one terminator, riding a real
    // packet, same as an arming-gate close would.
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);
    e->setWaitMillisForTest(1);

    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    for (int i = 0; i < 10; i++) { e->onPcm(quiet.data(), dumble::kTxPacketSamples); drain(*e); }

    const auto pcm = dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav"));
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    int outputs = 0;
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size() &&
                        outputs < kBurstPackets + 1;
         at += dumble::kTxPacketSamples) {
        e->onPcm(pcm.data() + at, dumble::kTxPacketSamples);
        while (e->pollPacket(out, sizeof(out), &fn, &flags) > 0) outputs++;
    }
    ASSERT_GE(outputs, kBurstPackets + 1) << "the spurt never opened";
    drain(*e);  // settle: nothing left buffered before the mode switch

    e->setTransmitMode(dumble::TransmitMode::PushToTalk);
    ASSERT_GT(e->pollPacket(out, sizeof(out), &fn, &flags), 0)
        << "the mode change produced no packet for the orphaned spurt";
    EXPECT_EQ(dumble::kFlagTerminator, flags & dumble::kFlagTerminator)
        << "a spurt in flight when the mode changed was orphaned without a terminator";
    EXPECT_EQ(0, e->pollPacket(out, sizeof(out), &fn, &flags))
        << "the mode-change terminator fired twice";
}

TEST(CaptureEngine, ABurstAfterAResetTransitionStillGetsAFullPreroll) {
    // Coverage carried over from the deleted collision test: a reset transition must not disable
    // the burst mechanism -- once the session resumes and silence refills the history, the next
    // spurt still gets its full preroll. Constructed with a mode-change reset instead of the
    // arming-gate collision, which Task 6's reset policy makes unreachable.
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);

    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    for (int i = 0; i < 10; i++) { e->onPcm(quiet.data(), dumble::kTxPacketSamples); drain(*e); }

    // A reset transition mid-session with no spurt in flight.
    e->setTransmitMode(dumble::TransmitMode::PushToTalk);
    drain(*e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    drain(*e);

    // Silence refills the history after the reset.
    for (int i = 0; i < 10; i++) { e->onPcm(quiet.data(), dumble::kTxPacketSamples); drain(*e); }

    const auto pcm = dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav"));
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    int maxPerIteration = 0;
    int collected = 0;
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size() && collected < 6;
         at += dumble::kTxPacketSamples) {
        e->onPcm(pcm.data() + at, dumble::kTxPacketSamples);
        int perIteration = 0;
        while (e->pollPacket(out, sizeof(out), &fn, &flags) > 0) { perIteration++; collected++; }
        if (perIteration > maxPerIteration) maxPerIteration = perIteration;
    }
    EXPECT_GE(maxPerIteration, kBurstPackets)
        << "the spurt after the reset transition lost its preroll burst";
}

TEST(CaptureEngine, AnArmingGateCloseMidSpurtEmitsExactlyOneTerminatorNotTwo) {
    // setGateOpen(false) raises both a terminator debt and resetDetectorPending_ in the same
    // call, and the reset claim can promote a debt of its own. However many owe-sites fire for
    // one spurt, they must fold into one debt, or the receiver gets two terminators for it.
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);
    e->setWaitMillisForTest(1);

    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    for (int i = 0; i < 10; i++) { e->onPcm(quiet.data(), dumble::kTxPacketSamples); drain(*e); }

    const auto pcm = dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav"));
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    int outputs = 0;
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size() &&
                        outputs < kBurstPackets + 1;
         at += dumble::kTxPacketSamples) {
        e->onPcm(pcm.data() + at, dumble::kTxPacketSamples);
        while (e->pollPacket(out, sizeof(out), &fn, &flags) > 0) outputs++;
    }
    ASSERT_GE(outputs, kBurstPackets + 1) << "the spurt never opened";

    e->setGateOpen(false);  // closes mid-spurt: transmittingSpurt_ is true here
    int terminators = 0;
    for (int i = 0; i < 4; i++) {
        const int bytes = e->pollPacket(out, sizeof(out), &fn, &flags);
        if (bytes <= 0) break;
        if (flags & dumble::kFlagTerminator) terminators++;
    }
    EXPECT_EQ(1, terminators) << "the arming-gate close produced " << terminators
                               << " terminators for one spurt";
}

TEST(CaptureEngine, AMuteAfterAModeChangeTerminatorStillClosesTheGate) {
    // The privacy hazard behind the terminator-debt split: when the mode-change terminator's
    // flush also drove the commanded arming state to Closed, the user's next mute matched the
    // repeat guard and no-opped -- gateOpen_ stayed true, onPcm kept capturing, and audio kept
    // transmitting after the user pressed mute. The arming state belongs to setGateOpen alone;
    // no terminator bookkeeping may move it.
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);
    e->setWaitMillisForTest(1);

    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    for (int i = 0; i < 10; i++) { e->onPcm(quiet.data(), dumble::kTxPacketSamples); drain(*e); }

    const auto pcm = dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav"));
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    int outputs = 0;
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size() &&
                        outputs < kBurstPackets + 1;
         at += dumble::kTxPacketSamples) {
        e->onPcm(pcm.data() + at, dumble::kTxPacketSamples);
        while (e->pollPacket(out, sizeof(out), &fn, &flags) > 0) outputs++;
    }
    ASSERT_GE(outputs, kBurstPackets + 1) << "the spurt never opened";
    drain(*e);

    // The mode change mid-spurt owes and flushes a terminator (pinned elsewhere) -- what matters
    // here is what that flush leaves behind.
    e->setTransmitMode(dumble::TransmitMode::PushToTalk);
    ASSERT_GT(e->pollPacket(out, sizeof(out), &fn, &flags), 0)
        << "the mode change produced no packet for the orphaned spurt";
    drain(*e);

    // Now the user presses mute. This must be a genuine close, not a repeat-guard no-op.
    e->setGateOpen(false);
    drain(*e);  // the mute's own terminator, if any

    const auto liveTone = tone(dumble::kTxPacketSamples);
    e->onPcm(liveTone.data(), uint32_t(liveTone.size()));
    EXPECT_EQ(0u, e->bufferedSamples())
        << "mute did not close the gate: audio is still entering the ring";
    EXPECT_EQ(0, e->pollPacket(out, sizeof(out), &fn, &flags))
        << "audio is still transmitting after the user pressed mute";
}

TEST(CaptureEngine, AMuteUnmuteUnderVoiceActivityIsASpurtEndNotAMerge) {
    // Under push-to-talk a close-then-reopen inside one poll interval merges into one continuous
    // transmission -- clockOffset_ and the ring carry it across. Voice activity has no such
    // continuity: every arming transition resets the detector, so the spurt in flight at the
    // mute genuinely ends. The reopen must not cancel the owed terminator, and it is a genuine
    // onset -- the encoder goes cold for whatever spurt the fresh detector opens next.
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);
    e->setWaitMillisForTest(1);

    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    for (int i = 0; i < 10; i++) { e->onPcm(quiet.data(), dumble::kTxPacketSamples); drain(*e); }

    const auto pcm = dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav"));
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    int outputs = 0;
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size() &&
                        outputs < kBurstPackets + 1;
         at += dumble::kTxPacketSamples) {
        e->onPcm(pcm.data() + at, dumble::kTxPacketSamples);
        while (e->pollPacket(out, sizeof(out), &fn, &flags) > 0) outputs++;
    }
    ASSERT_GE(outputs, kBurstPackets + 1) << "the spurt never opened";
    ASSERT_EQ(1u, e->encoderResets()) << "the spurt's own opening edge should be the only reset";
    const uint64_t lastDataFn = fn;

    e->setGateOpen(false);  // mute mid-spurt
    // A second of mic audio arrives while muted: counted by the clock, never captured. The
    // unmute below rewrites clockOffset_ to absorb this gap for the NEXT spurt -- the muted
    // spurt's terminator must keep the offset it was owed under, not inherit the new one.
    const std::vector<int16_t> mutedGap(dumble::kSampleRate, 0);
    e->onPcm(mutedGap.data(), uint32_t(mutedGap.size()));
    e->setGateOpen(true);   // unmute before the pump ever polls the close

    ASSERT_GT(e->pollPacket(out, sizeof(out), &fn, &flags), 0)
        << "the muted spurt was dropped without a terminator";
    EXPECT_EQ(dumble::kFlagTerminator, flags & dumble::kFlagTerminator)
        << "the muted spurt's packet did not carry the terminator";
    EXPECT_LT(fn, lastDataFn + 50)
        << "the terminator's frame number inherited the reopen's clock offset, jumping the "
        << "muted-gap's ~100 frames into the future of the spurt it ends";
    EXPECT_EQ(2u, e->encoderResets())
        << "the unmute merged into the dead spurt instead of starting cold";
    EXPECT_EQ(0, e->pollPacket(out, sizeof(out), &fn, &flags))
        << "the mute produced a second packet";
}

TEST(CaptureEngine, AMuteWhileTheDetectorIsIdleSendsNothing) {
    // Under voice activity the arming gate stays open for the whole session and the DETECTOR
    // owns the spurt. A mute while nothing is transmitting has nothing to terminate: flushing
    // one anyway put up to a packet of un-judged microphone audio on the wire -- the same class
    // of leak the reset policy exists to prevent -- plus a speaking-indicator blip at every
    // receiver, for a press that meant "send nothing".
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);
    e->setWaitMillisForTest(1);

    // Armed but idle: everything fed so far was judged silent and held, never transmitted.
    const std::vector<int16_t> quiet(dumble::kTxPacketSamples, 0);
    for (int i = 0; i < 10; i++) { e->onPcm(quiet.data(), dumble::kTxPacketSamples); drain(*e); }

    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags = 0;
    e->setGateOpen(false);
    EXPECT_EQ(0, e->pollPacket(out, sizeof(out), &fn, &flags))
        << "an idle mute put a packet on the wire";
    EXPECT_EQ(0, e->pollPacket(out, sizeof(out), &fn, &flags))
        << "an idle mute put a second packet on the wire";

    // And after a spurt the detector opened AND closed on its own: the terminator already went
    // out with the spurt's last packet, so a later mute still owes nothing -- a second
    // terminator would re-blip every receiver for a transmission that ended long ago.
    e->setGateOpen(true);
    const auto pcm = dumble::fixture::readWav(dumble::fixture::referencePath("synthetic.wav"));
    uint32_t lastFlags = 0;
    auto feed = [&](const int16_t* p) {
        e->onPcm(p, dumble::kTxPacketSamples);
        while (e->pollPacket(out, sizeof(out), &fn, &flags) > 0) lastFlags = flags;
    };
    for (size_t at = 0; at + dumble::kTxPacketSamples <= pcm.size(); at += dumble::kTxPacketSamples)
        feed(pcm.data() + at);
    for (int i = 0; i < 30; i++) feed(quiet.data());
    ASSERT_EQ(dumble::kFlagTerminator, lastFlags & dumble::kFlagTerminator)
        << "the detector never closed the spurt on its own";

    e->setGateOpen(false);
    EXPECT_EQ(0, e->pollPacket(out, sizeof(out), &fn, &flags))
        << "a mute after the detector already terminated the spurt double-terminated it";
}

TEST(CaptureEngine, AnIdleVoiceActivityMuteLeavesNothingBufferedForTheNextSpurt) {
    // An idle mute owes no terminator, which is correct — but that also means no flushTerminator,
    // and it is flushTerminator that used to reset the ring. Whatever the detector judged silent
    // and left mid-packet would then still be there at the next unmute, spliced into the first
    // packet of a spurt the user believes started clean. The history clear does not reach it: the
    // audio is in the ring, not in history_.
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);

    // Fed but never polled while the gate is open, so it sits in the ring un-judged. Frame-native
    // this IS a judgeable whole frame; only the missing poll keeps it there.
    const auto marker = tone(dumble::kTxPacketSamples / 2);
    e->onPcm(marker.data(), uint32_t(marker.size()));
    ASSERT_GT(e->bufferedSamples(), 0u) << "fixture failed to leave a partial packet buffered";

    e->setGateOpen(false);
    uint8_t out[dumble::kMaxPacketBytes];
    uint64_t fn = 0;
    uint32_t flags = 0;
    e->pollPacket(out, sizeof(out), &fn, &flags);   // the poll that claims the reset

    EXPECT_EQ(0u, e->bufferedSamples())
        << "pre-mute audio survived the mute and will be spliced into the next spurt";
}

TEST(CaptureEngine, AnOddBurstShipsItsLeftoverFrameWithTheNextLiveOne) {
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);
    e->setWaitMillisForTest(1);
    static_assert(dumble::kPrerollFrames + 1 == 7,
                  "this test's arithmetic assumes a seven-slot queue");

    // Fill the queue with silence, one frame at a time.
    const std::vector<int16_t> quiet(dumble::kFrameSamples, 0);
    for (int i = 0; i < 12; i++) { e->onPcm(quiet.data(), dumble::kFrameSamples); drain(*e); }
    ASSERT_EQ(7, e->heldFramesForTest()) << "the queue should be full of silent preroll";

    // Then speech, fed a frame at a time so the detector sees frame granularity.
    const auto pcm = dumble::fixture::readWav(
        dumble::fixture::referencePath("synthetic.wav"));
    std::vector<uint64_t> frameNumbers;
    // Contiguity below still catches a genuine drop (a forward skip past the floor), but the
    // frame_number clamp is upward-only, so it is blind to a candidate that only LAGS the floor --
    // a burst that trickles one frame at a time, or a pop that only ever takes one real frame,
    // both just re-derive the same evenly-spaced sequence with less audio behind it. Packet count
    // over the held preroll catches that instead: a real burst pops the 6 committed preroll frames
    // two at a time (3 packets) in the SAME drain the onset frame triggers, with no further input
    // needed; a pop that only ever takes one frame needs 6.
    //
    // Neither check decodes payload, so "right count, wrong frames" (e.g. zeroing a packet, or
    // copying from historyOldest_+1) would still pass both. No test in this suite asserts positive
    // payload fidelity: the only two that decode Opus at all,
    // DoubleCloseWithNoInterveningPollNeverEncodesGateClosedAudio (:261) and
    // ARedundantCloseNeverFlushesGateClosedAudio (:390), assert absence of loud audio, which an
    // all-zero packet also satisfies. See TODO.md.
    int maxPerFeed = 0;
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    for (size_t at = 0; at + dumble::kFrameSamples <= pcm.size() && frameNumbers.size() < 5;
         at += dumble::kFrameSamples) {
        e->onPcm(pcm.data() + at, dumble::kFrameSamples);
        int thisFeed = 0;
        while (e->pollPacket(out, sizeof(out), &fn, &flags) > 0) {
            frameNumbers.push_back(fn);
            thisFeed++;
        }
        if (thisFeed > maxPerFeed) maxPerFeed = thisFeed;
    }
    ASSERT_GE(frameNumbers.size(), size_t(4)) << "the burst never drained";
    EXPECT_EQ(kBurstPackets, maxPerFeed)
        << "the held preroll did not pop as " << kBurstPackets << " two-frame packets "
        << "in one drain — either it trickled out with nothing committed at once (too few), or "
        << "each pop only took one frame instead of a full packet's worth (too many)";

    // Contiguity is the claim: consecutive packets are exactly kFramesPerPacket frames apart, with
    // no gap where the burst's seventh frame would have been silently dropped.
    for (size_t i = 1; i < frameNumbers.size(); i++) {
        EXPECT_EQ(frameNumbers[i - 1] + dumble::kFramesPerPacket, frameNumbers[i])
            << "packet " << i << " skipped a frame — the odd burst's leftover was dropped";
    }
}

TEST(CaptureEngine, ASpurtEndingOnAnOddFrameStillShipsThatFrame) {
    auto e = engine();
    ASSERT_TRUE(e);
    e->setTransmitMode(dumble::TransmitMode::VoiceActivity);
    e->setGateOpen(true);
    e->setWaitMillisForTest(1);

    const auto pcm = dumble::fixture::readWav(
        dumble::fixture::referencePath("synthetic.wav"));
    uint8_t out[dumble::kMaxPacketBytes]; uint64_t fn; uint32_t flags;
    bool sawTerminator = false;
    // Latched only on the feed that produces the terminator: was readyFrames_ 0 entering it (the
    // odd, single-leftover close this test is named for -- only "closing && readyFrames_ > 0"
    // ships that) or 1 (an even close, which "readyFrames_ >= kFramesPerPacket" ships on its own
    // and would still pass with that clause deleted)? Mid-spurt, heldFramesForTest() and
    // readyFrames_ are the same number -- every queued frame is committed once transmitting -- so
    // it doubles as that reading without a dedicated accessor. Without this, a one-frame drift in
    // the fixture silently flips this into a test of the even path, and mutation 2 stops failing.
    int heldBeforeClosingFeed = -1;
    // Whether the terminator flag rode the FIRST packet this feed produced. A debtWaiting drain
    // ships its short packet unflagged, with the real (flagged) flushTerminator trailing on the
    // NEXT poll of the SAME drain -- sawTerminator alone cannot tell that apart from the
    // detector's own closing packet, which carries the flag immediately.
    bool terminatorOnFirstPacketOfClosingFeed = false;
    auto drainFeed = [&](int heldBefore) {
        bool first = true;
        while (!sawTerminator) {
            const int bytes = e->pollPacket(out, sizeof(out), &fn, &flags);
            if (bytes <= 0) break;
            if ((flags & dumble::kFlagTerminator) != 0) {
                sawTerminator = true;
                heldBeforeClosingFeed = heldBefore;
                terminatorOnFirstPacketOfClosingFeed = first;
            }
            first = false;
        }
    };
    // synthetic.wav's own trailing silence is past the hangover on its own -- the fixture's speech
    // ends around frame 329 of 400, so the detector, not the arming gate, closes the spurt before
    // the file even runs out. Flags must be checked on every feed, not only after the fixture is
    // exhausted: a loop that discards them until then would just miss this close.
    for (size_t at = 0; at + dumble::kFrameSamples <= pcm.size() && !sawTerminator;
         at += dumble::kFrameSamples) {
        const int heldBefore = e->heldFramesForTest();
        e->onPcm(pcm.data() + at, dumble::kFrameSamples);
        drainFeed(heldBefore);
    }
    // Belt and braces in case the fixture ever changes: keep feeding silence past the hangover.
    const std::vector<int16_t> quiet(dumble::kFrameSamples, 0);
    for (int i = 0; i < 200 && !sawTerminator; i++) {
        const int heldBefore = e->heldFramesForTest();
        e->onPcm(quiet.data(), dumble::kFrameSamples);
        drainFeed(heldBefore);
    }
    EXPECT_TRUE(sawTerminator) << "the detector's close must ship a terminator whatever the parity";
    EXPECT_EQ(0, e->heldFramesForTest())
        << "a committed frame left queued at spurt end is audio the close silently ate";
    EXPECT_EQ(0, heldBeforeClosingFeed)
        << "the close landed on an even frame instead of the odd leftover this test is named for "
        << "-- a fixture drift silently converted this into a test of nothing";
    EXPECT_TRUE(terminatorOnFirstPacketOfClosingFeed)
        << "the terminator trailed a later packet in the same drain instead of riding the first "
        << "-- the wire got extra silence between the audio and its terminator";
}
