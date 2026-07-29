package me.danielstiner.dumble.mumble.voice

/** Opus encoder bitrate, handed to [NativeCapture.create].
 *
 *  One fixed rate has to serve every network, and the client cannot yet tell a metered connection
 *  from an unmetered one, so it is priced for the metered case. 32 kb/s keeps fullband speech on
 *  SILK — libopus puts the mono speech CELT crossover at 64 kb/s (`mode_thresholds[0][0]`,
 *  opus_encoder.c) and OPUS_APPLICATION_VOIP raises that threshold another 8 kb/s — and it is the
 *  floor of the desktop client's own AUDIO-preset band. Network-aware tiers are future work. */
const val TRANSMIT_BITRATE = 32_000

/** 20 ms packets. Pinned natively by CaptureConstants.h `kTxFrameSamples`; mirrored here only for
 *  the accounting below, which is why it is private and never reaches the encoder. */
private const val PACKETS_PER_SECOND = 50

/** Per-packet framing above the Opus payload: the UDP type byte, the Audio protobuf's own tags
 *  and lengths, and the tunnel's TCP message header. An estimate — it only ever feeds a warning,
 *  never traffic shaping. */
private const val FRAMING_BYTES_PER_PACKET = 37

/** What we cost as the server accounts us, for comparison against its `max_bandwidth`. Derived
 *  rather than written down so it cannot drift from [TRANSMIT_BITRATE] — the drift would be
 *  invisible, since the whole point of the comparison is to explain a symptom the server reports
 *  no other way. */
const val ACCOUNTED_BITRATE =
    TRANSMIT_BITRATE + FRAMING_BYTES_PER_PACKET * PACKETS_PER_SECOND * 8
