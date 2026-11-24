# Latency check

`scripts/latency-check` runs Dumble's adb-only latency check on a device: it starts a throwaway
Murmur in Docker, points the device at it, launches the check, pulls and prints
`latency-<time>.txt`, and removes the container. Needs adb, Docker and openssl. Exit 0 only for
`result = ok`, 1 for any other result, 2 when the check could not run (no device, not installed,
no alias, no results in 90 s).

```
scripts/latency-check [--usb | --lan [--tcp]] [--host <address>] [-s <serial>] [--package <id>]
```

With more than one device attached, name one with `-s` or `ANDROID_SERIAL`. The screen must be on
and unlocked. The script grants the package the microphone permission (and the local network
permission for `--lan`) and leaves it granted; it changes nothing else on the device and installs
nothing.

| Flag | What it measures |
|---|---|
| `--usb` (default) | `adb reverse` to the computer. TCP only, so voice takes the TCP tunnel. Steady, needs no shared network; the one to compare builds with. |
| `--lan` | The computer's LAN address: the real UDP path over Wi-Fi, with its latency and jitter. Needs port 64739 open for TCP and UDP. |
| `--lan --tcp` | Publishes only TCP, so voice falls back to the tunnel over the same Wi-Fi. Against `--lan` it isolates what UDP buys. |

`--lan` finds the computer's LAN address itself (`en0` on macOS, the default route on Linux);
`--host <address>` overrides it, e.g. with a VPN or Ethernet.

Docker Desktop forwards ports through its own proxy; the round trip includes that hop.

`loop.phone` includes the jitter buffer's wait, which follows how packets arrive and so differs
between the modes and from run to run. To compare two builds, compare `unaccounted`, or
`loop.phone` beside `receive.jitter_buffer`.

## What it prints

The check sends a probe through Murmur's loopback and plays the return through the speaker. t0:
the packet carrying the probe's onset is sent; t1: it comes back; t2: the onset leaves capture.
The device writes `/sdcard/Android/data/<package>/files/latency/latency-<yyyyMMdd-HHmmss>.txt`
and logs its path under the tag `LatencyCheck`; the script pulls and prints it. Durations in ms.

| Row | Meaning |
|---|---|
| `result` | `ok`, `not_detected` (the probe came back but capture did not hear it), `failed` or `refused` |
| `reason`, `likely_cause` | Why, when not `ok` |
| `device.*`, `route`, `voice_call_volume` | The phone, the audio route, and the voice-call volume out of its maximum |
| `voice_path` | `udp` or `tcp` (the tunnel), at t0 |
| `capture.frames_per_burst` | Capture's burst size, in frames |
| `effects`, `effects_off` | The capture effects found, and whether all were turned off |
| `network.round_trip` | t1 − t0: the onset's packet to Murmur and back |
| `network.ping` | Dumble's own ping on the leg carrying voice, for comparison |
| `loop.phone` | t2 − t1: playout, speaker, air, microphone and capture |
| `receive.jitter_buffer` | What playout held ahead of the probe's packet when it arrived |
| `receive.gate.target`, `receive.gate.write_ahead` | The two parts of the depth a spurt waits for before it plays: the jitter estimator's target and the output stream's write-ahead. A jitter buffer well past their sum is a burst that arrived at once |
| `receive.shrunk_packets`, `receive.catch_up_packets` | Packets playout dropped to shed delay during the check. Above zero, the probe may have waited less than `receive.jitter_buffer` says, and `unaccounted` reads low |
| `receive.codec_delay` | Opus's own delay, encoder to decoder |
| `receive.audio_output` | The output stream's latency, from Oboe |
| `send.input_buffer` | The input stream's latency, from Oboe |
| `send.encode` | One packet's duration plus the mean encode time |
| `unaccounted` | `loop.phone` less the jitter buffer, codec delay, audio output, input buffer and encode: what Dumble cannot read |
| `detection.peak`, `detection.next_peak` | The probe's match in capture and the best other; found needs the first ≥ 0.1 and 5× the second |

An emulator's microphone may be the computer's, so a check there can return `ok` or
`not_detected`; neither describes a phone. The probe's level is `PROBE_PEAK` in `ProbeExchange.kt`
(−26 dBFS). Measured on a phone, its match is 11 to 14 times the next best.

## Testing beside your own app

The script tests whatever `--package` names (default `me.danielstiner.dumble`). To leave your own
app and its data alone on a personal phone, build a copy with a local `applicationIdSuffix`
(`.latencytest`) in `app/build.gradle.kts`, uncommitted, and install it by hand:

```
./gradlew :app:assembleDebug
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
scripts/latency-check --package me.danielstiner.dumble.latencytest
```

Never use Gradle `install…` or `connected…` tasks for this: they install on every attached device
and can replace or uninstall your own app. A debug build is signed with a different key, so
installing it over the release app needs its data wiped; the suffix avoids that. The script
refuses a package without the latency alias. The launch always names the full class
`me.danielstiner.dumble.LatencyCheck`, which the suffix does not change.
