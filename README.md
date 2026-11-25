# Dumble

An Android voice-chat client for [Mumble](https://www.mumble.info/) servers, version 1.5 and newer:
channel tree, text chat, and Opus voice with push-to-talk or voice-activated transmit.

- **Package:** `me.danielstiner.dumble` · **minSdk** 31 (Android 12) · **targetSdk** 37
- **Kotlin:** Jetpack Compose, Hilt, coroutines, protobuf-lite. One Gradle module, `:app`.
- **C++20:** the audio engines, on Oboe and a vendored libopus, built into `libdumble.so`.

## Build

```bash
git submodule update --init --recursive   # libopus and GoogleTest live in third_party/
./gradlew assembleDebug
```

Needs the Android SDK and a JDK to launch Gradle (Android Studio's bundled one works). Gradle
fetches the JDK 21 toolchain itself, and the Android Gradle plugin installs the pinned NDK and
CMake on first build.

A release build is signed only when `DUMBLE_KEYSTORE_FILE`, `DUMBLE_KEYSTORE_PASSWORD`,
`DUMBLE_KEY_ALIAS` and `DUMBLE_KEY_PASSWORD` are set as Gradle properties (put them in
`~/.gradle/gradle.properties`). Pushing a `v*` tag makes CI build the signed APK and attach it to
the GitHub release.

## Test

| Suite | Command |
|---|---|
| JVM unit tests, Compose tests under Robolectric | `./gradlew testDebugUnitTest` |
| C++ core, on the host | `cmake -S app/src/main/cpp/host -B build/host && cmake --build build/host -j && ctest --test-dir build/host --output-on-failure` |
| Against a real server | see below |
| On a device | `./gradlew connectedDebugAndroidTest` |
| Lint and the shipped-dependency check | `./gradlew lintDebug verifyShippedGroups` |

CI runs all of these except the device tests, which it only compiles. Kotlin, C++ and lint
warnings are errors.

**Real server.** `LiveServerIntegrationTest` is skipped unless `MUMBLE_TEST_SERVER` is set:

```bash
docker compose -f docs/dev/mumble-server/docker-compose.yml up -d
MUMBLE_TEST_SERVER=localhost ./gradlew testDebugUnitTest --tests "*LiveServerIntegrationTest"
```

**Device tests.** Use an emulator: the Android Gradle plugin uninstalls the app when the run ends,
and its data goes with it.

**Shipped-dependency check.** `verifyShippedGroups` compares what the release build ships (Maven
groups, submodules, native libraries, assets) with the lists in `app/src/test/resources/shipped-*.txt`.
When they differ it rewrites the list and fails. Attribute the newcomer in
`ui/about/Attribution.kt`, which feeds the About screen, and commit the diff.

## Documentation

Start with [architecture](docs/architecture.md). Each subsystem has its own page:

| Page | Covers |
|---|---|
| [docs/architecture.md](docs/architecture.md) | the layers, the threads, where the code lives |
| [docs/network.md](docs/network.md) | connecting, reconnecting, certificates, choosing UDP or TCP for voice |
| [docs/mumble-protocol.md](docs/mumble-protocol.md) | the wire protocol as this client speaks it |
| [docs/capture.md](docs/capture.md) | microphone to packets, the transmit gate, sharing the microphone |
| [docs/voice-activity.md](docs/voice-activity.md) | the voice activity detector: design, constants, measurements |
| [docs/playout.md](docs/playout.md) | packets to the speaker, the jitter buffer, measured latency |
| [docs/latency.md](docs/latency.md) | the on-device latency check: how to run it and what its rows mean |
| [docs/avatar-color.md](docs/avatar-color.md) | the specification for a user's avatar color |

`AGENTS.md` holds the coding conventions, the non-goals and the test rules.

## License

Apache 2.0, see `LICENSE`. Third-party notices are on the About screen.
