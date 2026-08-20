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

State inventory, evidence limits, pending exceptions and capture commands are in [docs/matchup-matrix.md](docs/matchup-matrix.md) and [docs/matchup-manifest.json](docs/matchup-manifest.json). The debug-only Android catalog currently captures 27 states with `scripts/capture-android-catalog.sh`; original PNGs are under `docs/screenshots/android`. The clean iOS welcome baseline and its OS permission surface are under `docs/screenshots/ios`.

## Known platform boundary

- Manifest permissions: `INTERNET`, `RECORD_AUDIO`, `CAMERA`, `FLASHLIGHT`, `POST_NOTIFICATIONS` (API 33+), `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE` (API 31+), with legacy Bluetooth/location declarations scoped to their applicable older APIs.
- Declare BLE feature support as optional unless product policy intentionally excludes unsupported devices.
- A concrete Android push provider is not present in the supplied iOS repository. `PushTokenProvider` therefore remains an explicit boundary; BLE and configured HTTP sending work, but process-absent remote receiving needs FCM credentials/server support.
- Foreground ding-dong audio is synthesized to the iOS frequencies/timing. The notification channel is versioned; exact custom channel audio would require adding the licensed source audio as `res/raw/dingdong3`.
- Background BLE is **not guaranteed**. Current BLE work is lifecycle-safe foreground best effort. Process death, force-stop, Doze, OEM restrictions and Android background-start rules can stop scanning/advertising. A foreground service would require a persistent user-visible notification plus service declarations/types and should be added only as an explicit product decision.

The implementation pass verified `testDebugUnitTest` and `assembleDebug`. No commit, push, publication, physical-device installation, or external deployment is performed by these commands.
