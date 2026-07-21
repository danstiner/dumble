# Dumble

An Android voice-chat client for [Mumble](https://www.mumble.info/) servers.

- **Package:** `me.danielstiner.dumble`
- **minSdk:** 30 (Android 11) · **targetSdk:** 36
- **UI:** Jetpack Compose

## Provenance

This `main` is a deliberate, production-oriented rebuild. The original prototype
(full feature set, ~339 commits) is preserved on the
[`vibed-prototype`](https://github.com/danstiner/dumble/tree/vibed-prototype) branch
and mined feature-by-feature into human-reviewed PRs here.

See [`docs/superpowers/specs/2026-07-21-dumble-production-rebuild-design.md`](docs/superpowers/specs/2026-07-21-dumble-production-rebuild-design.md)
for the rebuild plan, scope, and PR roadmap.

## Development

Requires Android SDK and a JDK (Android Studio's bundled JBR works).

```bash
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # unit tests
```

Every change lands as a reviewed pull request against `main`.
