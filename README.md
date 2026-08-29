# OurButton Android

Android phone/tablet counterpart to the iOS `OurButton` family-call app. It includes the Compose UI/state catalog, iOS-compatible QR/JSON/BLE codecs, real BLE central/peripheral transport, audio/torch/recording/notification integrations, APNs-parallel FCM receiving through the shared HTTP backend, and 2×2 parent/child widgets. Full visual parity is not claimed because the iOS project has no deterministic state fixture catalog for paired capture.

Current app version is `2.0.2`, versionCode `346493`, display build `202608291453`. Settings can automatically or manually check and download the allowed signed APK from the app's official GitHub Releases channel before handing it to Android's installer.

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

The recipient picker also merges the authenticated server directory with nearby BLE presence, so every remote-notification-capable device in the family space remains individually selectable at a distance. Users can save, create, join and switch between separate family rooms; remote registration is maintained for every configured room. The large square home actions are `톡톡`, `띵동`, and `음성` for both parent and child roles, with purpose-matched tap, ringing-bell and microphone icons. Voice recording waits for an explicit send or cancel decision instead of transmitting on release.

Call history is capped at the newest 20 entries across saved rooms. Sent entries show the remaining recipient count at the far right, decrement once per acknowledging device, and hide the count at zero.

## Known platform boundary

- Manifest permissions: `INTERNET`, `RECORD_AUDIO`, `CAMERA`, `FLASHLIGHT`, `POST_NOTIFICATIONS` (API 33+), `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `BLUETOOTH_ADVERTISE` (API 31+), with legacy Bluetooth/location declarations scoped to their applicable older APIs.
- Declare BLE feature support as optional unless product policy intentionally excludes unsupported devices.
- FCM uses data-only messages (`eventID`, `spaceID`, `kind`). Android performs an authenticated event fetch, then validates the fetched space, kind and optional `targetID` before showing anything. Firebase's current installation-ID registration callbacks and WorkManager handle cold/background delivery and token re-registration; automatic registration stays off until the user enables remote notifications.
- Firebase identifiers are intentionally not committed. Put these values from the Android app registration for package `com.armsone.button` in user/CI Gradle properties: `BUTTON_FIREBASE_APPLICATION_ID`, `BUTTON_FIREBASE_PROJECT_ID`, `BUTTON_FIREBASE_API_KEY`, and `BUTTON_FIREBASE_SENDER_ID`. When they are absent, the app still builds and its Bluetooth/server-sending behavior remains available, while the UI reports that Firebase setup is needed.
- The NAS/server needs its own Firebase service-account credential and FCM HTTP v1 configuration. Never put that service-account JSON in this public Android repository; client identifiers and the server service-account serve different purposes.
- Foreground ding-dong audio is synthesized to the iOS frequencies/timing. The versioned notification channel uses the original iOS `dingdong3.wav` bundled as `res/raw/dingdong3`.
- A five-second quiet-button hold sends a remote siren event without sounding the sender. The receiver uses the bundled `siren.wav` and a dedicated notification channel.
- Parent calls have no cooldown. The 10-second send cooldown remains limited to the child role.
- Parent and child voice recording starts from the `음성` home action, asks for confirmation before sending, plays once when the recipient enters the app, and remains replayable from call history.
- Background BLE is **not guaranteed**. Current BLE work is lifecycle-safe foreground best effort. Process death, force-stop, Doze, OEM restrictions and Android background-start rules can stop scanning/advertising. A foreground service would require a persistent user-visible notification plus service declarations/types and should be added only as an explicit product decision.

The implementation pass verified `testDebugUnitTest`, `assembleDebug`, and `assembleRelease`, then installed versionCode 13 with data preservation and cold-launched it on SM-F968N and SM-T500. Version 1.1 (build 14) adds mixed APNs/FCM remote delivery. Version 1.1.1 (build 15) disables Android backup so the family-space secret and device membership cannot leave the device through cloud backup or device transfer. Version 1.1.3 (build 17) adds the remote siren, direct hold-to-send voice flow, parent-only unlimited sending, and centered recording/countdown status.
