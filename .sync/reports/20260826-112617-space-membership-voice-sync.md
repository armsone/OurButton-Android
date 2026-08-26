# Button space, membership, and voice synchronization run

## Result

The affected Apple, Android, and server sources were reconciled around multi-space persistence, switching, token-independent membership, and reliable call/voice delivery semantics. Android focused tests and its debug build pass, and the server owner reported 27/27 tests passing.

This run is **not fully synchronized**. Matchup's ledger gate exits 3 because all four affected functional rows lack post-change two-device behavior traces. No paired screenshots were captured, the Apple test/build result was not supplied to this Android owner, and no physical-device runtime verification was authorized.

## Timing

- Time zone: Asia/Seoul (KST)
- Observable Android-owner edit start: 2026-08-26 11:16:12 KST
- Report finalized: 2026-08-26 11:29:21 KST
- Observable elapsed: 13 minutes 9 seconds
- Timing limitation: the formal monotonic start was not preserved across the Gemini timeout and Hooke takeover. The start above is the earliest filesystem timestamp attributable to the retained completion edits; total orchestration elapsed is therefore unavailable and is not estimated.

## Resolved synchronization group

- Registry group: `button`
- Canonical contract path: `/Users/armsone/git/button-Android/.sync/product-contract.yaml`
- Apple member: `/Users/armsone/git/button` at committed HEAD `21082ff`, with uncommitted task changes
- Android member: `/Users/armsone/git/button-Android` at committed HEAD `c2ab58e`, with uncommitted task changes
- Shared server: `/Users/armsone/git/button/server`, part of the Apple repository worktree
- Registry baseline update: not performed. Both repositories are dirty and the parity ledger gate failed, so neither current worktree is represented by a verified Git HEAD.
- Contract limitation: the existing contract records the affected capability family but is not an exhaustive first-full-sync inventory in the schema required by Project Sync. It cannot support a full-product synchronization claim.

## Actual synchronization table

| Capability | Reference / intended outcome | Apple state | Android state | Server state | Technical/runtime evidence | Matchup evidence | Verdict |
|---|---|---|---|---|---|---|---|
| Setup create/join local persistence | Creating or joining activates that space immediately without erasing other rooms or depending on push | Source changed in `AppModel.swift`; lifecycle tests were added but their execution result was not supplied here | Implemented in `AppViewModel.kt`; focused lifecycle JVM tests pass | Membership persistence separated from push devices | Android source + JVM persistence tests; Apple runtime not observed | Ledger row `setup_create_join.space_records.persistence` is open | source-only |
| Settings space switch and relaunch | Every saved room remains selectable; selected room, role, name, history, and transport context survive relaunch | `switchRoom`/active membership source changed | Atomic switch and leave-next-room transition implemented; relaunch test passes | Not applicable to local selection | Android JVM evidence only; no UI interaction trace | `settings_switch.active_space.action` open; no captures | source-only |
| Token-independent membership | Membership is registered on the existing `/v1/devices` endpoint even when APNs/FCM token is absent; a later token refresh updates delivery without erasing membership | Empty-token registration source added | Optional token omitted while all saved memberships sync on state and foreground | Existing endpoint now stores `memberships.json` separately and retains an existing push token on blank registration | Server owner reported 27/27 tests; Android backend payload test passes | No dedicated visual row; member-list runtime remains missing | source-only |
| Symmetric QR member visibility | Creator and participant both see the same authoritative member set even when notifications are off; BLE presence may merge without owning membership | Backend refresh source changed | Membership sync is followed by active-space member refresh; periodic/foreground refresh retained | `/members` now reads independent memberships | Source and server tests; no Android A/B trace | Covered indirectly by setup row; runtime remains open | source-only |
| Outbound active-space routing | A call uses the space ID, secret, sender name, and role captured when Send is pressed, even if the user switches before completion | Apple pending-call source binds event and space | Immutable `OutboundContext`; history records under captured space | Same event ID/payload is idempotent; conflicting reuse returns 409 | Android voice coding and space snapshot tests pass | `voice_send.active_space.routing` open | source-only |
| Voice send success/error and retry | Voice remains retryable until the backend confirms a reachable delivery; no success is shown for zero delivery; retry is bounded and idempotent | Pending remote call/retry and user error source added | Requires `delivered > 0`, maps `delivery_unavailable`, retries same ID at 500 ms and 1500 ms only for IO/429/5xx, retains READY on failure | Event is persisted before delivery; duplicate same payload is idempotent; tokenless/unreachable delivery returns 503; push failure returns 502 | Android backend/voice tests and build pass; server 27/27 reported; no live FCM trace | `voice_send.error.delivery_confirmation` open; no positive/negative runtime traces or captures | source-only |
| Pending received voice per space | A background voice for one room is not consumed while another room is active and can recover after relaunch | Space-keyed pending references added | Pending voice preference is keyed by space with legacy preservation | Stored event TTL supports authenticated fetch after push | Source only on both apps; no process-death trace | No closed behavior evidence | source-only |

## Per-project validation and state

| Project / component | Local source | Tests | Build | Git | Install | Release / publication |
|---|---|---|---|---|---|---|
| Apple app | Uncommitted affected source and new tests present | Not supplied to Hooke; treat as unverified here | Not supplied; unverified here | Dirty; no commit or push | Not run | Not released; no TestFlight or NasFinder work |
| Android app | Implemented locally in six production files, two new tests, contract and ledger | 43 affected JVM tests passed with 0 failures/errors; final lifecycle/backend subsets passed again | `./gradlew assembleDebug` passed after final source; debug APK 27 MB | Dirty; no commit or push | Not run by instruction | Not released; no GitHub Release or NasFinder work |
| Shared server | Uncommitted membership/idempotency/voice validation changes present | Server owner Mencius reported 27/27 passing | Node service build is not a separate artifact in the supplied evidence | Dirty within Apple repository; no commit or push | Not deployed | Production server state is unchanged/unverified |

Android commands and observed results:

```text
git diff --check
  passed

./gradlew testDebugUnitTest --tests <8 affected test classes>
  BUILD SUCCESSFUL; 43 tests, 0 failures, 0 errors

./gradlew testDebugUnitTest --tests SpaceLifecycleTest --tests BackendClientTest
  BUILD SUCCESSFUL after final persistence edit

./gradlew testDebugUnitTest --tests BackendClientTest
  BUILD SUCCESSFUL after final error-text edit

./gradlew assembleDebug
  BUILD SUCCESSFUL after final source

ruby -e 'require "yaml"; YAML.load_file(".sync/product-contract.yaml")'
  yaml ok
```

## Matchup ledger gate

- Ledger: `/Users/armsone/git/button-Android/.parity/ledger.json`
- Rows maintained: setup create/join persistence, settings switch action, voice active-space routing, voice delivery/error feedback
- Source/test evidence is recorded in observations and attempts, but no row is falsely closed without a runtime behavior trace.
- No screenshots were captured, so visual parity and responsive phone/tablet/TV rendering remain unverified.

Command:

```text
python3 /Users/armsone/.codex/skills/matchup/scripts/validate_parity_ledger.py \
  /Users/armsone/git/button-Android/.parity/ledger.json --gate
```

Exact result:

```text
Structure OK: 4 row(s), 0 complete, 4 open
GATE FAILURE: 4 blocking item(s)
  - [setup_create_join.space_records.persistence] status 'implemented_source_only' blocks completion (only matched or fully proven forced_os_exception counts; attempt counts are metrics, never evidence)
  - [settings_switch.active_space.action] status 'implemented_source_only' blocks completion (only matched or fully proven forced_os_exception counts; attempt counts are metrics, never evidence)
  - [voice_send.active_space.routing] status 'implemented_source_only' blocks completion (only matched or fully proven forced_os_exception counts; attempt counts are metrics, never evidence)
  - [voice_send.error.delivery_confirmation] status 'implemented_source_only' blocks completion (only matched or fully proven forced_os_exception counts; attempt counts are metrics, never evidence)
```

Exit code: `3`. This intentionally blocks a synchronized or matched claim.

## Errors and resolutions

| Stage | Observed error | Cause | Corrective action | Retry/result | Open? |
|---|---|---|---|---|---|
| Initial Android delegation | Gemini timed out after leaving an untrusted partial draft | Provider/task timeout; no remaining Gemini process | Hooke reviewed the entire diff and affected source, preserved unrelated `.sync`, corrected the draft, and revalidated from source through build | Android focused tests and build passed | Resolved for source; Gemini runtime evidence unavailable |
| Membership contract audit | Draft attempted blank-token registration while the then-read server rejected blank FCM tokens | Client/server contract was temporarily inconsistent | Reported blocker to TM; Mencius updated the existing `/v1/devices` endpoint with optional-token membership semantics; Android omits absent token | Server 27/27 reported and Android payload test passed | Deployment remains open |
| First expanded Android test run | Kotlin compile failed at `connection.errorStream?.use { ... }.orEmpty()` and subsequent JSON inference | Nullable byte-array expression was not inferred by the Android Kotlin compiler | Replaced it with explicit `?: ByteArray(0)` before JSON decoding | The same focused suite passed on the next run | No |
| Delivery semantics audit | Configured backend BLE enqueue was reported as Sent before backend delivery; server `delivered: 0` was ignored | Transport API has no BLE application-delivery ACK and client treated any 2xx as success | Removed early BLE success for configured backend, parsed delivery count, mapped 503, and added bounded same-ID retry | Backend tests and build passed | BLE-only delivery acknowledgement remains unavailable |
| Matchup gate | Validator exited 3 with four open functional rows | Physical two-device traces and paired post-change captures were not collected | Kept all rows `implemented_source_only`; did not weaken the gate or invent evidence | Structure valid; gate correctly fails | Yes, expected evidence gap |

## Intentional and current platform differences

- Android BLE is foreground, best-effort, and intentionally rejects voice payloads; voice uses the authenticated server path. This is a current transport limit, not verified parity.
- No LAN transport is implemented in the Android repository. The requested global fallback order therefore cannot be claimed complete.
- APNs and FCM are platform-native push providers. Token-independent membership is shared, but remote/background voice still requires a valid provider token and a deployed compatible server.
- Android phone/tablet/Google TV source support was preserved, but no affected screen was captured on those classes in this run.

## Unfinished items

1. **Two-device create/join and symmetric list trace — Apple/Android/server**
   - Complete: source implementation and token-independent server membership contract.
   - Missing: Android A creates, Android B QR-joins, both refresh and show the same list with notification permission off; repeat after relaunch.
   - Reason: physical devices were explicitly excluded from Hooke's task.
   - Next safe action: install a verified build on two test Android devices without deleting data and record the exact input, server response, member lists, and relaunch result.

2. **Space switch runtime and rendered UI — Apple/Android**
   - Complete: source persistence and Android JVM relaunch test.
   - Missing: behavior traces and paired captures for settings list, active marker, switch result, and relaunch on phone plus representative tablet/TV layout.
   - Reason: no simulator/emulator/device or screenshot capture was run.
   - Next safe action: run deterministic matching fixtures on both platforms, capture post-change pairs, hash them, and append evidence to the ledger.

3. **Voice positive and negative delivery traces — Apple/Android/server**
   - Complete: voice bytes validation, immutable space context, server idempotency, bounded Android retry, and recoverable error source.
   - Missing: FCM/APNs accepted delivery, notifications-off active-app behavior, push unavailable 503, transient 502/timeout retry, switch-during-send, receive/playback, background/relaunch recovery, and paired error-state captures.
   - Reason: provider credentials, deployed updated server, and physical runtime were outside this owner's validation.
   - Next safe action: deploy the already-tested server only under authorized release/deployment scope, then collect positive and forced-negative two-device traces with the same event IDs and voice hashes.

4. **LAN/direct delivery and BLE acknowledgement — Android**
   - Complete: existing BLE source was preserved and backend now avoids false success.
   - Missing: LAN transport and an application-level BLE receipt/ACK.
   - Reason: neither exists in the current Android transport contract; adding them is a larger product/protocol change and was not invented during this repair.
   - Next safe action: define the shared transport receipt and LAN discovery/security contract first, implement compatibly on both platforms, and add wire/runtime evidence before claiming fallback-order completion.

5. **Apple validation and full canonical contract inventory**
   - Complete: affected Apple source and tests are present in its dirty worktree.
   - Missing: Apple test/build results in this report and an exhaustive product-contract inventory with source coverage.
   - Reason: Hooke owned only Android completion and received no Apple validation result.
   - Next safe action: Apple owner runs focused tests/build and TM consolidates results; a later full sync inventories all remaining product capabilities before updating verified HEADs.

## Agent assignments, outcomes, and usage evidence

| Agent/provider | Assignment | Outcome | Remaining usage evidence |
|---|---|---|---|
| Gemini | Initial Android implementation | Timed out; partial draft treated as untrusted and superseded by Hooke review | Gemini weekly `59.6066% → 58.3226%` (decrease `1.2840pp`); five-hour `100% → 92.2959%` (decrease `7.7041pp`) |
| Hooke / Codex | Android takeover, correction, tests, build, contract, ledger, report | Completed source/build verification; runtime gaps left open | Codex weekly `92% → 91%` (decrease `1pp`) |
| Claude Fable | Apple counterpart work under TM orchestration | Affected source and tests are present; validation result not supplied to Hooke | Fable session `32% → 29%` (decrease `3pp`); Claude weekly `65% → 64%` (decrease `1pp`) |
| Mencius | Shared server membership, idempotency, and voice validation | Server owner reported 27/27 tests passing | No separate usage value supplied |
| TM Codex | Requirements, assignments, cross-member integration | Integration still pending parity/runtime evidence | Included only in shared Codex weekly value above |

The provider values were supplied by TM. They are shared provider/session measurements; per-agent token attribution is unavailable and no split is inferred.

## Final state

- Local implementation: present, uncommitted
- Android tests: passed
- Android build: passed
- Apple tests/build: unverified in this report
- Server tests: 27/27 reported passed
- Matchup structure: valid
- Matchup completion gate: failed with four honest open rows
- Git commit/push: not performed
- Installation/device run: not performed
- Deployment/release/publication/NasFinder: not performed
- Full synchronization: **not claimed**
