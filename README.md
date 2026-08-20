# 버튼 Android

Android phone/tablet counterpart to the iOS `버튼` family-call app. It includes the Compose UI/state catalog, iOS-compatible QR/JSON/BLE codecs, real BLE central/peripheral transport, audio/torch/recording/notification integrations, HTTP fallback, and 2×2 parent/child widgets. Full visual parity is not claimed because the iOS project has no deterministic state fixture catalog for paired capture.

## Build and test

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # only when one selected device is reserved for this task
```

Use a task-specific Gradle/build directory when concurrent work could share machine resources. Do not run connected tests against multiple devices accidentally.

## Matchup capture

State inventory, evidence limits, pending exceptions and capture commands are in [docs/matchup-matrix.md](docs/matchup-matrix.md) and [docs/matchup-manifest.json](docs/matchup-manifest.json). The debug-only catalog captures 32 deterministic states on both a physical phone and tablet; original PNGs are under `docs/screenshots/android-phone` and `docs/screenshots/android-tablet`. The clean iOS welcome baseline and its OS permission surface are under `docs/screenshots/ios`.

Build 13 also matches the latest iOS one-recipient calling contract: `targetID` remains an optional v1 JSON field, non-target devices relay without alerting, ACKs return to the original sender and are displayed only when correlated to a recently sent call, and parent/child homes expose recipient selection plus sent/acknowledged activity banners.

## Known platform boundary

- Manifest permissions: `INTERNET`, `RECORD_AUDIO`, `CAMERA`, `FLASHLIGHT`, `POST_NOTIFICATIONS` (API 33+), `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE` (API 31+), with legacy Bluetooth/location declarations scoped to their applicable older APIs.
- Declare BLE feature support as optional unless product policy intentionally excludes unsupported devices.
- A concrete Android push provider is not present in the supplied iOS repository. `PushTokenProvider` therefore remains an explicit boundary; BLE and configured HTTP sending work, but process-absent remote receiving needs FCM credentials/server support.
- Foreground ding-dong audio is synthesized to the iOS frequencies/timing. The notification channel is versioned; exact custom channel audio would require adding the licensed source audio as `res/raw/dingdong3`.
- Background BLE is **not guaranteed**. Current BLE work is lifecycle-safe foreground best effort. Process death, force-stop, Doze, OEM restrictions and Android background-start rules can stop scanning/advertising. A foreground service would require a persistent user-visible notification plus service declarations/types and should be added only as an explicit product decision.

The implementation pass verified `testDebugUnitTest`, `assembleDebug`, and `assembleRelease`, then installed versionCode 13 with data preservation and cold-launched it on SM-F968N and SM-T500. GitHub publication remains a separate release step.
