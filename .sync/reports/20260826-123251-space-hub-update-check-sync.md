# Button Space Selection latest-version check synchronization

## Result

The bottommost Space Selection action now exposes the platform-native latest-version check on Apple and Android without duplicating either update implementation. Android reuses `DirectUpdateManager`; it does not introduce a second network/version/download path. Source, focused tests, and builds pass on both app owners' supplied evidence.

This run is **not fully synchronized or visually matched**. The Matchup gate exits 3 with eight open functional rows. The new update-check row has source/test/build evidence but no paired state captures or controlled live update/error trace. All three authorized phones now have the latest tested app build installed with data preserved.

## Timing and group

- Time zone: Asia/Seoul (KST)
- Start checkpoint: 2026-08-26 12:26:32 KST
- Final execution checkpoint: 2026-08-26 12:34:46 KST
- Observable wall-clock elapsed: 8 minutes 14 seconds
- Initial implementation/validation monotonic checkpoint at 12:32:51: 379.262290833 seconds (about 6 minutes 19 seconds); the subsequent second-Android connection/install extension did not provide a new monotonic value.
- Registry group: `button`
- Canonical contract: `/Users/armsone/git/button-Android/.sync/product-contract.yaml`
- Apple member: `/Users/armsone/git/button`
- Android member: `/Users/armsone/git/button-Android`
- Server: unaffected; no server read/write/deploy was required for this capability
- Verified registry HEAD values were not advanced because both app worktrees remain uncommitted and the parity gate is open.

## Actual synchronization table

| State/capability | Apple | Android | Server | Verdict |
|---|---|---|---|---|
| Hierarchy | Bottommost update `Section` in scrollable Space Selection form, `SpaceSelectionView.swift:75` | `SpaceSelectionUpdateCheck` is the last child of `AdaptiveContent`, `ButtonApp.kt:406,410` | Not applicable | Source aligned |
| Default | Full-width `최신 버전 확인`, `SpaceSelectionView.swift:108` | Full-width, D-pad/accessibility-actionable `최신 버전 확인` | Not applicable | Text/action aligned |
| Checking | Non-action progress and `확인 중…`, `:112-117` | Non-action progress and `확인 중…`; click disabled | Not applicable | State/text aligned |
| Current | Non-action `최신 버전이에요`, `:119-122` | Non-action `최신 버전이에요` | Not applicable | State/text aligned |
| Update available | `버전 {version} 업데이트 열기` opens the existing App Store/TestFlight-safe destination | `버전 {version} 다운로드` invokes the existing validated GitHub-release download route | Not applicable | Intent aligned; platform-native action |
| Download/verified handoff | Apple opens official destination and never claims download/install | Existing manager alone reports actual download; `설치 화면 열기` appears only after size/hash/package/version/signer validation and hands off to Android installer | Not applicable | Truthful platform adaptation |
| Error/retry | Actionable error footer plus `다시 확인`; optional official TestFlight action | `확인하지 못했어요. 연결을 확인하고 다시 시도해 주세요.` plus `다시 확인` | Not applicable | Core error/retry aligned; Apple-only store fallback intentional |
| Shared updater | Existing `AppUpdateChecker.swift` | Existing `DirectUpdateManager.kt`; only a pure presentation mapper and hub renderer were added | Not applicable | No duplicate updater/dependency |

## Android changes and validation

- `app/src/main/java/com/armsone/button/ui/ButtonApp.kt`
  - Appended the update state/action as the bottommost scroll content.
  - Uses full width, minimum 52dp, standard Compose focus semantics, stable tags, and non-action semantics for checking/current.
- `app/src/main/java/com/armsone/button/update/DirectUpdateManager.kt`
  - Added only `SpaceHubUpdatePresentation` state mapping.
  - Existing check, official release validation, download, APK verification, and installer handoff are unchanged and reused.
- `app/src/test/java/com/armsone/button/update/DirectUpdatePolicyTest.kt`
  - Covers default, checking disabled/progress, current, versioned available action, verified handoff wording, and actionable retry.
- `.sync/product-contract.yaml` and `.parity/ledger.json`
  - Added the affected observable behavior and one source-only parity row.

Validation order/results:

```text
git diff --check
  passed

./gradlew testDebugUnitTest --tests <9 affected test classes>
  BUILD SUCCESSFUL; 53 tests, 0 failures, 0 errors

./gradlew assembleDebug
  BUILD SUCCESSFUL
  app-debug.apk: 28,470,778 bytes

JSON ledger parse and YAML contract parse
  passed
```

Apple evidence supplied by Newton:

- `AppUpdateCheckerTests.swift`: latest for same/lower version, numeric `1.10 > 1.9` available result with destination, and actionable missing-info error.
- Focused tests: 16/16 passed; final checker subset: 3/3 passed.
- Signed generic build, embedded widget validation, and deep codesign passed.

## Installation state

Requested latest-install targets total three phones:

1. Apple iPhone: latest build update-installed with data retained; Newton reported same database UUID and successful relaunch.
2. Android `SM_F968N` at `192.168.0.199:34119`: final APK update-installed with `install -r`; versionCode 340680, versionName 2.0.1; `button_state.xml` retained; relaunch command succeeded.
3. Android `SM-S928N`, serial `R3CX10FTE1L`: update-installed successfully. It was initially absent/refusing connections; after wireless debugging was enabled it appeared through existing paired mDNS transports, including the refreshed `192.168.0.224:32827` service. Model/serial were verified before installation. The final APK was installed with `install -r`; versionCode 340680, versionName 2.0.1; the pre-existing `button_state.xml` remained after installation; relaunch succeeded and the app task was visible/resumed.

No emulator, uninstall, clear, new pairing, security-setting mutation, or speculative target was used. Phone installation is complete for the authorized Apple phone and both Android phones.

## Matchup ledger gate

Command:

```text
python3 /Users/armsone/.codex/skills/matchup/scripts/validate_parity_ledger.py \
  /Users/armsone/git/button-Android/.parity/ledger.json --gate
```

Result:

```text
Structure OK: 8 row(s), 0 complete, 8 open
GATE FAILURE: 8 blocking item(s)
```

Exit code: `3`. The new `space_selection.latest_version_check.action` row remains `implemented_source_only`, as do the seven carried-forward rows. No screenshot, source-only, test, build, or install attempt is promoted to matched evidence.

## Errors and resolutions

| Error | Cause | Resolution | Remaining state |
|---|---|---|---|
| Second Android target initially absent from ADB snapshot | Wireless debugging endpoint was not accepting connections | Existing mDNS discovery found `R3CX10FTE1L`; after the user enabled wireless debugging, refreshed ADB/mDNS exposed the paired device and new port. Verified `SM-S928N`/`R3CX10FTE1L`, then installed with `-r` and confirmed state/version/relaunch | Resolved; all three phone installs complete |
| Cross-platform update paths differ | Apple uses official App Store/TestFlight destinations; Android uses the established verified direct APK route | Matched state meanings/text while retaining each platform's existing secure next action | Intentional OS adaptation |
| No live check/capture evidence | This run used source/JVM/build/install verification only | Kept parity row open and gate failing | Runtime/visual verification unfinished |

## Usage evidence and agents

| Provider remaining window | Start | End | Change |
|---|---:|---:|---:|
| Claude Fable | 29% | 29% | 0pp |
| Claude weekly | 64% | 64% | 0pp |
| Gemini five-hour | 82.186651% | 82.186651% | 0pp |
| Gemini weekly | 56.637698% | 56.637698% | 0pp |
| Codex weekly | 90% | 90% | 0pp |

- Newton: Apple update-check hierarchy/text, tests, build, and Apple install evidence.
- Hooke: Android implementation, tests, build, two Android data-preserving installs, contract/ledger/report.
- TM Codex: requirements, coordination, integration decision.

The values are shared provider/session remaining totals measured at the supplied 12:26:32 start and 12:32:51 usage checkpoint. Per-agent attribution is unavailable and no split is inferred.

## Unfinished items

1. Capture paired default/checking/current/available/error states on Apple and Android phones.
2. Capture Android tablet and Google TV/D-pad focus behavior for the bottommost scroll action.
3. Run controlled live official-route checks for latest, available, network error, actual download progress, verified APK handoff, cancel, and retry without publishing or changing a release.

## Final state

- Local source/contract/ledger/report: complete and uncommitted
- Apple tests/build/one-phone install: reported passed
- Android tests/build/two-phone installs: passed with both app states preserved
- Git commit/push: not performed
- Release/deploy/site/version/build change: not performed
- Full synchronization or visual match: **not claimed**
