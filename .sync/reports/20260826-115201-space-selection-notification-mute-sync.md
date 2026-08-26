# Button space-selection and per-space notification-mute synchronization

## Result

Apple, Android, and server source behavior is aligned for the affected space-selection and per-space current-device notification-mute capabilities. Android focused tests/build and an update install on the one reachable Android phone passed. Apple focused tests/build and its one reachable iPhone update install passed. The shared server owner reported all tests passing.

This run is **not fully synchronized or visually matched**. The Matchup gate exits 3 because seven functional rows, including the three newly affected rows, still lack paired post-change runtime/capture evidence. No server deployment was authorized, and no two-device mute/call/voice scenario was run.

## Timing

- Time zone: Asia/Seoul (KST)
- Observable Android feature edit start: 2026-08-26 11:40:51 KST
- End checkpoint: 2026-08-26 11:53:59 KST
- Observable elapsed: 13 minutes 8 seconds
- Limitation: this is the retained Android-owner filesystem edit window. Total cross-agent orchestration elapsed was not measured with a shared monotonic clock.

## Synchronization group

- Registry group: `button`
- Canonical contract: `/Users/armsone/git/button-Android/.sync/product-contract.yaml`
- Apple: `/Users/armsone/git/button` (dirty task worktree)
- Android: `/Users/armsone/git/button-Android` (dirty task worktree)
- Shared server: `/Users/armsone/git/button/server` (dirty Apple-repository worktree)
- Verified Git HEAD registry values were not advanced because worktrees are uncommitted and the Matchup gate remains open.

## Actual synchronization table

| Capability | Apple evidence | Android evidence | Server evidence | Result |
|---|---|---|---|---|
| Home top-left Space Selection entry | Parent `ParentHomeView.swift:51`, child `ChildHomeView.swift:48`, route in `RootView.swift:8,40` | Home parent/child top-left `공간 선택` in `ButtonApp.kt:179` | Not applicable | Source/build aligned; no paired capture |
| First-launch-style hub hierarchy | `SpaceSelectionView.swift:13-81`: saved spaces/current, `저장된 공간`, create, join, current QR | `ButtonApp.kt:351`: all rooms, current state, `저장된 공간`, `새 공간 만들기`, `다른 공간 참여하기`, current QR | Not applicable | Source/build aligned; settings no longer owns switch/create/join |
| Immediate space switch and relaunch | `AppModel.swift:267` and existing persistence | `AppViewModel.kt:519`; existing two-space relaunch JVM coverage | Membership remains per space | Source/test aligned; no tap/relaunch runtime trace |
| Per-space own-device mute toggle | `AppModel.swift:273`, persistence/retry at `:93,1108,1153` | `AppViewModel.kt:644`, `SpaceNotificationMute.kt`; local SharedPreferences change precedes queued network work | Existing `POST /v1/devices`, boolean `notificationsMuted`, same-value request idempotent | Source/test/build aligned |
| Honest inline synchronization state | Apple own row/hub inline state in `UIComponents.swift:331`, `SpaceSelectionView.swift:77-81`; background retry does not set global error | Own row and hub show `동기화 중`/`동기화 필요`; pure reducer preserves `errorMessage == null` | 204 success; 400/401 terminal; transient failure remains retryable | Regression tests aligned; forced-offline rendering not captured |
| Exact member mute visibility | `BackendClient.swift:42,49,112`, `AppModel.swift:950` | `/members` decoding in `BackendClient.kt:137`; remote tile shows exact `알림 꺼짐` | `/members` always returns boolean; legacy/missing data is false | Contract/test aligned |
| Incoming alert suppression without data loss | Apple AppDelegate/router/model/history paths at `AppDelegate.swift:40,51`, `RemoteNotificationRouter.swift:8`, `AppModel.swift:788,962` | BLE/foreground and FCM/background record authenticated event/voice before shared mute guard; current playback/flash/notifications are stopped/cleared on mute | Muted targets skip APNs/FCM but event remains in 10-minute authenticated fetch store | Source/test aligned; no live muted call/voice trace |
| Membership/token preservation | Apple partial device update omits token/name/role | Android toggle POST omits token/name/role; normal membership registration remains active | Existing token/name/role/membership preserved | Server concurrency/preservation tests passed |
| All-muted and mixed send truthfulness | Apple handles queued/muted response separately | Android parses `muted` and `queued`; all-muted is shown as quiet storage, not delivery; mixed success uses actual delivered count | All muted: 202 `{delivered:0,attempted:0,muted:N,queued:true}`; mixed reports actual counts plus muted | API/source/test aligned |

## Validation and state

| Component | Local/source | Tests | Build | Install/runtime | Git | Release/deploy |
|---|---|---|---|---|---|---|
| Apple | Affected source and tests present, uncommitted | Newton reported focused 13/13 passed | Signed generic iOS build, widget validation, deep codesign passed | Reachable iPhone18,1 update-installed without erase; database UUID retained; relaunched | No commit/push | No TestFlight/release |
| Android | Affected production source, tests, contract, ledger, report present, uncommitted | Focused JVM suite 51 tests, 0 failures/errors | Final `assembleDebug` passed; APK 28,463,572 bytes | Reachable `SM_F968N` updated with `adb install -r`; package versionCode 340680/versionName 2.0.1; `button_state.xml` remained; activity start command succeeded | No commit/push | No GitHub Release/NasFinder |
| Server | `server.mjs`, tests, README changed in Apple worktree | Mencius reported 29/29 plus node syntax and diff checks passed | No separate artifact | Not deployed; production behavior unverified | No commit/push | No deploy/release |

Android validation order and result:

```text
git diff --check
  passed

./gradlew testDebugUnitTest --tests <8 affected test classes>
  BUILD SUCCESSFUL; 51 tests, 0 failures, 0 errors

./gradlew assembleDebug
  BUILD SUCCESSFUL after final hub/nonblocking-error changes

python3 -m json.tool .parity/ledger.json
ruby YAML.load_file(.sync/product-contract.yaml)
  passed
```

## Matchup gate

Command:

```text
python3 /Users/armsone/.codex/skills/matchup/scripts/validate_parity_ledger.py \
  /Users/armsone/git/button-Android/.parity/ledger.json --gate
```

Exact result summary:

```text
Structure OK: 7 row(s), 0 complete, 7 open
GATE FAILURE: 7 blocking item(s)
```

Exit code: `3`. Open rows are create/join persistence, Space Selection switching, voice active-space routing, voice delivery feedback, current-device mute, muted incoming suppression, and nonblocking inline sync failure. All remain `implemented_source_only`; source/tests/build/install attempts do not substitute for paired behavior/capture evidence.

## Errors and resolutions

| Error | Cause | Resolution | State |
|---|---|---|---|
| First `adb devices -l` shell attempt returned `command not found` | `adb` was not on PATH | Located existing SDK binary at `/Users/armsone/Library/Android/sdk/platform-tools/adb`; ran the actual device listing once with that binary | Resolved |
| Only two of the requested three phones were reachable across the paired platforms | One Apple phone and one Android phone were visible; the third phone was not visible/reachable | Update-installed the reachable Apple and Android phones without erasing data; did not invent or reconnect an absent target | Installation unfinished for the third phone |
| Initial WorkInfo observer compile mismatch | Android lifecycle LiveData exposes nullable `WorkInfo` | Changed observer to `Observer<WorkInfo?>`; focused suite then passed | Resolved |
| Post-install source refinement added immediate clearing of an already-visible current-space alert | First installed APK preceded the final refinement | Rebuilt, reran focused tests, and update-installed the final APK again with `-r` | Resolved |
| Recoverable activation/relaunch mute sync could have produced repeated blocking UI in the cross-platform flow | Global error presentation is inappropriate for background retry | Android keeps the desired local state in per-space inline sync state only; Apple owner made the same regression fix; tests assert no global modal state | Resolved in source/tests; runtime capture open |

## Agent assignments and outcomes

- Hooke: Android implementation, source review, JVM tests, build, update installation, contract/ledger/report — completed with runtime gaps left open.
- Newton: Apple mute and Space Selection counterpart, regression tests, signed build, update install — reported completed.
- Mencius: existing server contract, membership mute state, delivery filtering/counts, concurrency and recovery tests — reported 29/29 completed.
- TM Codex: requirements, assignment, integration and completion decision.

Measured provider remaining values supplied for this run:

| Provider window | Start remaining | End remaining | Change |
|---|---:|---:|---:|
| Claude Fable | 29% | 29% | 0pp |
| Claude weekly | 64% | 64% | 0pp |
| Gemini weekly | 58.3123% | 56.6377% | decrease 1.6746pp |
| Gemini five-hour | 92.2340% | 82.1867% | decrease 10.0473pp |
| Codex weekly | 91% | 90% | decrease 1pp |

These are shared provider/session totals. Per-agent attribution is unavailable, so no split among Hooke, Newton, Mencius, and TM is inferred.

## Unfinished items

1. Capture paired Apple/Android Space Selection screens and interactions on phone plus representative tablet/TV layouts; verify D-pad focus order and immediate home return.
2. Force offline mute off/on, relaunch repeatedly, and capture inline `동기화 중`/`동기화 필요` without a blocking modal; then restore network and verify both devices show the same member status.
3. Run two-device call and voice cases while target is muted, confirm no sound/flash/card/notification, verify history/voice recovery, unmute, and confirm push resumes.
4. Installation is complete on 2 of the requested 3 phones: one Apple and one Android. The third phone was not visible/reachable. Exact next action: the user connects and unlocks the third phone using USB or Wi-Fi debugging/trust as appropriate, then the platform owner reruns the update install without uninstalling or clearing data.
5. Deploy the tested server only under separate deployment authorization before claiming production mute behavior.

## Final state

- Local source/tests/contract/ledger/report: complete and uncommitted
- Android focused tests/build: passed
- Reachable phone update installs: 2 of 3 passed with app data retained (one Apple, one Android)
- Third requested phone install: unfinished because the phone was not visible/reachable
- Apple focused tests/build/reachable-phone update install: reported passed
- Server tests: reported 29/29 passed
- Git commit/push: not performed
- Server deployment/release/website: not performed
- Full synchronization or visual match: **not claimed**
