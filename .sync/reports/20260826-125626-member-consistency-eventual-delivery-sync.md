# Member consistency, general role, and eventual delivery sync

- contract_path: `/Users/armsone/git/button-Android/.sync/product-contract.yaml`
- parity_ledger_path: `/Users/armsone/git/button-Android/.parity/ledger.json`
- group: `button` (Apple client, Android client, server)
- start checkpoint: `2026-08-26 12:40:10 KST` / monotonic `159476585789000`
- end checkpoint: `2026-08-26 12:56:26 KST` / monotonic `160452203270000`
- elapsed: `975.617481s` (about 16m16s)

## Result

Android source, focused tests, and debug APK build are complete for the reopened member-list inconsistency, compact-card truncation, `general` role, and offline/no-route delivery flow. One reachable Android phone received the final APK with data-preserving `install -r` and relaunched successfully. The second authorized Android phone was absent from both the ADB snapshot and mDNS discovery, and its previously verified endpoint no longer exists; its final installation remains unfinished.

Full synchronization is not claimed. The parity ledger remains open because post-change three-device behavior, paired captures, tablet/TV rendering, and production server deployment are not yet evidenced.

## Actual synchronization table

| Capability | Apple | Android | Server | Current conclusion |
|---|---|---|---|---|
| Durable three-device roster | Local self + authoritative `/members`, deviceID dedup, BLE live-only, stale-space rejection; focused tests passed (`AppModel.swift:108-142,959-1075`) | Same data rule in `AppViewModel.kt:68,691-725`; generation gate in `MemberRefreshGate.kt`; focused tests passed | One membership per space/deviceID independent of token, mute, connectivity, and lastSeen; serialized upsert/migration; server tests 34/34 | Source/test aligned; post-change three-device capture open |
| Compact member cards | Phone one full-width row, two-line name, separate role/live/notification states (`UIComponents.swift:240-350`) | One column below 520dp, dynamic full-width card, two-line clip-free name, separate role/notification/live lines (`ButtonApp.kt:740-823`) | Not applicable | Source aligned; Android phone hierarchy confirms full-width rows, tablet/TV and paired visual capture open |
| Durable vs live counts | Header separates registered member count from live transport badge | `구성원 N명` and `근처 N대` are separate (`ButtonApp.kt:737-749,825-841`) | `/members` is durable membership only | Source/runtime hierarchy aligned on one Android; cross-device count equality still open |
| `general` / `일반` role | Wire `general`, label `일반`, parent route/behavior (`FamilyRole.swift:4-35`, `RootView.swift:21-31`) | `FamilyRole.General` / `AppRole.GENERAL`, parent home/cooldown behavior, persistence and API/event mapping | Accepts and preserves `parent|child|general` in membership, events, FCM/APNs, and inbox; same permissions | Source/test aligned; runtime role selection and cross-device display open |
| No-route targeted send | Same-ID outbox, nonblocking `전송 대기`, no recoverable global modal (`AppModel.swift:690-785`) | Event is written to `OutboundEventStore` before transport; UI says `전송 대기`; recoverable route/server failure stays queued without modal | Existing call POST stores a valid membership target and returns queued acceptance even if tokenless/offline/muted | Source/test aligned; production behavior blocked until server deployment |
| Inbox recovery | GET inbox → dedup/process → ACK → cursor; foreground/relaunch/transport/64s refresh (`BackendClient.swift:120-171`, `AppModel.swift:974-1054`) | Same endpoint/ACK, opaque cursor persistence, eventID dedup, active-space guard, foreground/relaunch/connectivity/32s visible polling (`BackendClient.kt:154-184`, `AndroidHardwareGateway.kt:633-666`) | 100/page, unACKed re-exposure, idempotent per-device ACK, 10-minute TTL, 1,000 cap | Contract/source/test aligned; production runtime pending deployment |
| Notification mute interaction | Preserves history/inbox while suppressing app-owned presentation | Existing per-space local protection and server sync retained; inbox processing records before suppression | Muted target stays a member, event is stored, push is suppressed | No regression found in focused tests; live muted recovery trace open |

## Screenshot evidence that reopened the rows

The original files were not altered. Temporary source paths are recorded in the parity ledger with hashes; no duplicate binary was created in the repository.

| Original | Dimensions | SHA-256 | Observation |
|---|---:|---|---|
| `IMAGE 2026-08-26 12:39:16.jpg` | 549×1280 | `b84daddffb18895d54d91c531ff77d023bfb5b85017202c815a7fc9a411c4ebd` | Same space showed 2 durable members |
| `IMAGE 2026-08-26 12:39:19.jpg` | 588×1280 | `733a22a9e5911373f8b7386ab015ddb71d62aa67170148a481482075b3868019` | Same space showed 1 member and truncated compact card |
| `IMAGE 2026-08-26 12:39:23.jpg` | 653×1280 | `c84d8be3f875339d3fe95f9c19e1cb3a6254c0095b2a34bc95e9e71df47c42db` | Same space showed 3 members |
| `IMAGE 2026-08-26 12:40:55.jpg` | 549×1280 | `7e42048b39f1f49d2646aefda36897ac718592fce353c2f256d0b0202bd9b6f4` | Selected durable member then 톡톡 produced blocking no-route modal |

## Android changes and evidence

- `app/src/main/java/com/armsone/button/state/AppViewModel.kt`: authoritative member union, stable deviceID dedup, BLE live-only state, distinct status copy, `general` role, honest waiting history copy.
- `app/src/main/java/com/armsone/button/platform/MemberRefreshGate.kt`: rejects older concurrent and old-space member responses.
- `app/src/main/java/com/armsone/button/platform/AndroidHardwareGateway.kt`: lifecycle/bounded member refresh, durable outbox, same-ID retry, inbox poll/dedup/ACK/cursor flow, `general` mapping.
- `app/src/main/java/com/armsone/button/data/OutboundEventStore.kt`: durable atomic outbox storage.
- `app/src/main/java/com/armsone/button/data/BackendClient.kt`: exact queued/acknowledged receipt semantics and inbox/ACK contract.
- `app/src/main/java/com/armsone/button/model/FamilyRole.kt`: backward-compatible `general` wire role.
- `app/src/main/java/com/armsone/button/ui/ButtonApp.kt`: compact full-width cards, adaptive wide columns, two-line names, separate role/notification/live states, separate counts, general role card, waiting copy.
- `app/src/test/java/com/armsone/button/state/MemberConsistencyTest.kt`: three-device equal-set, BLE non-authority, duplicate dedup, long name, adaptive columns, concurrent/stale response, general role.
- `app/src/test/java/com/armsone/button/data/BackendClientTest.kt`: offline queued acceptance, acknowledged retry, exact inbox/ACK request, general member decode.

## Validation

1. `git diff --check` — passed.
2. Disk gate before build — 237 GiB available, above the 50 GiB/10% requirement.
3. `./gradlew testDebugUnitTest --tests 'com.armsone.button.data.BackendClientTest' --tests 'com.armsone.button.state.SpaceLifecycleTest' --tests 'com.armsone.button.state.MemberConsistencyTest' --tests 'com.armsone.button.model.CallEventCodingTest'` — passed, 34 tests, 0 failures/errors/skips.
4. `./gradlew assembleDebug` — passed.
5. Final APK: `/Users/armsone/git/button-Android/app/build/outputs/apk/debug/app-debug.apk`, 28,443,085 bytes, SHA-256 `b4e273c215544e41315c67ca4925998241e92b1ef1694a15dd6b9e2348290b11`.
6. Reachable Android runtime hierarchy — confirmed `공간 선택`, separate `근처 1대` / `구성원 2명`, two full-width member rows, separate `역할: 부모` / `알림 켜짐` / `이 기기|근처 연결됨`, and existing history now rendered as `전송 대기 중이에요`; no blocking modal was present on relaunch.
7. Mencius server evidence — `npm test` 34/34, node checks and server diff check passed; not deployed by this task.
8. Newton Apple evidence — effective focused 39/39 after correcting one test-only date precision assertion; signed build/codesign and reachable iPhone install/relaunch passed.
9. `python3 /Users/armsone/.codex/skills/matchup/scripts/validate_parity_ledger.py /Users/armsone/git/button-Android/.parity/ledger.json --gate` — structure passed (`11 row(s), 0 complete, 11 open`), gate failed with 11 blocking rows. This is expected and prevents a full synchronization claim.

## Errors and resolutions

| Error | Cause | Resolution/state |
|---|---|---|
| Earlier Gemini attempt timed out and left an untrusted partial Android draft | External collaborator timeout | Hooke reviewed the full Android diff, corrected the draft, replaced false-success handling, and reran focused tests/build |
| Initial `adb devices -l` command returned `adb: command not found` | SDK platform-tools was not on shell PATH | Located existing `/Users/armsone/Library/Android/sdk/platform-tools/adb` and used that explicit binary for the single current ADB snapshot and subsequent commands |
| Focused test compile failed on misplaced `isLiveNearby` property | Patch context placed a presence-only field on `SavedRoomUi` | Removed the invalid field, set it only on current `PresenceUi`, and reran tests successfully |
| Inbox contract test initially compared nanosecond and encoded second precision | `CallEventCoder` intentionally truncates `sentAt` to seconds | Asserted stable eventID and role wire fields; all focused tests passed |
| Runtime hierarchy exposed `음성를` | Existing particle branch was grammatically incorrect | Unified call titles to the correct `을` suffix, rebuilt, retested, and reinstalled final APK |
| Second Android unavailable | Current ADB snapshot listed only `SM_F968N`; mDNS listed only `adb-R3KYB061JTZ` at `192.168.0.199:34119`; previously verified `192.168.0.224:32827` returned `device not found` | Unfinished. User must unlock `SM-S928N`/`R3CX10FTE1L`, keep it on the same Wi-Fi, and enable Wireless debugging; then rerun data-preserving `install -r` and relaunch |

## Installation manifest

| Target | Discovery | Install | Version/state | Launch |
|---|---|---|---|---|
| Android `SM_F968N` | `192.168.0.199:34119`, model verified in ADB snapshot | Final APK `install -r` succeeded; no uninstall/clear | `versionName=2.0.1`, `versionCode=340680`; existing space/member/history state remained visible | `com.armsone.button/.MainActivity` top resumed |
| Android `SM-S928N`, `R3CX10FTE1L` | Absent from current ADB and mDNS; previous endpoint not found | Not installed in this final pass | Existing prior build state unknown after latest source change | Not launched |
| Apple iPhone18,1 | Newton evidence | Latest Apple build data-preserving update install succeeded | Existing database retained | Relaunch succeeded |

## State separation

- Local source: modified and preserved in the existing dirty worktree.
- Tests: focused Android 34/34 passed; Apple effective 39/39; server 34/34.
- Build: Android debug APK passed; Apple signed build passed per Newton.
- Git: no commit, push, PR, tag, or version/build bump performed.
- Install: Apple latest complete; one of two Android targets final-complete; second Android unfinished due connectivity.
- Release/deploy/site: not performed. The new server contract is verified locally but not deployed, so production inbox/general/queued delivery cannot yet be claimed.

## Usage and agent assignments

| Provider | Start remaining | End remaining | Decrease | Attribution |
|---|---:|---:|---:|---|
| Codex weekly | 90% | 89% | 1pp | Shared Codex value; per-agent attribution unavailable |
| Claude weekly | 64% | 64% | 0pp | Shared provider value; per-agent attribution unavailable |
| Claude Fable | 29% | 29% | 0pp | Shared provider value; per-agent attribution unavailable |
| Gemini weekly | 56.637698% | 56.637698% | 0pp | Shared provider value; per-agent attribution unavailable |
| Gemini five-hour | 82.186651% | 82.186651% | 0pp | Shared provider value; per-agent attribution unavailable |

- End Codex credits remaining: `26389.711161` (fresh=true, age 19s at checkpoint).
- End Claude snapshot: fresh=true, account snapshot age 383s.
- End Gemini snapshot: fresh=true, age 1s.
- Hooke: Android implementation review/completion, tests, build, install/runtime inspection, contract/ledger/report.
- Newton: Apple counterpart implementation and evidence; effective 39/39, signed build/install evidence supplied.
- Mencius: server general/member consistency/inbox/queued-delivery implementation and evidence; 34/34 supplied.
- Gemini: earlier Android draft timed out; no final trusted result. Hooke reviewed and completed it.

## Unfinished items

1. Connect the second Android `SM-S928N`/`R3CX10FTE1L` as described above, then update-install the final APK, relaunch, and capture the post-change three-device equal roster.
2. Deploy the verified server changes before expecting production `general`, queued tokenless delivery, or inbox recovery.
3. Capture post-change paired phone views plus representative Android tablet/TV D-pad views; visual/content rows remain reopened.
4. Exercise a production or controlled deployed-server targeted offline 톡톡 and voice flow through queue → recipient inbox → ACK → sender history confirmation.
5. Close the 11 ledger rows only with new post-change paired behavior/capture evidence; the current gate correctly fails.

## 13:15 KST addendum — member-name visibility regression

The latest direct report that names were not visible reopened the compact member-card check. Android now maps blank/whitespace names to `가족`, trims displayed names, sets the member-name text to explicit charcoal contrast, keeps soft wrapping, removes the hard line cap, and retains dynamic card height. Focused `MemberConsistencyTest` + `SpaceLifecycleTest` and `assembleDebug` passed. The final APK was update-installed with `install -r` to `SM_F968N` and relaunched without clearing data.

Post-install UI Automator exposed visible text nodes `대표` and `대표 아이폰` plus complete accessibility descriptions. Pixel inspection confirms both names visibly rendered in separate full-width cards. Evidence: `/Users/armsone/git/button-Android/.sync/evidence/button-member-name-visible-sm-f968n-20260826.png`, 822×1918, SHA-256 `c9a30e51f5cf6769bb50e015f2a5935bf1b5390c401ecc0fd7ed6a5b29df16c3`.

Addendum validation: 17 focused tests passed (MemberConsistency 4, SpaceLifecycle 13), `assembleDebug` passed, and the final addendum APK SHA-256 is `fdc19bec1e8bf3040d3cf308984cd8a2fcde9271b1ae76a0f0326ba0ffab92ae`. Ledger structure remains valid; its 11 open rows continue to fail the completion gate honestly.

## 13:20 KST addendum — why 김부장 is absent

A read-only, secret-redacted comparison on `SM_F968N` confirmed one saved/active local space and one local self membership (`대표`). The production `/members` response for that exact active space contained one authoritative row, `대표 아이폰`; it contained neither `김부장` nor the current Android self. Local push registration state had the correct same-space membership but no registered fingerprint, which means its latest backend registration has not been confirmed.

`김부장` is not simply known only from another local space: the device history contains five recent received 김부장 events whose space hash exactly matches the active space hash. Those events can arrive through BLE while the durable server membership remains absent. The Android merge is therefore behaving as specified—local self + authoritative `/members`, with BLE permitted only to mark an existing durable member live—and must not invent a durable 김부장 row.

The confirmed direct cause is missing production membership registration for 김부장, not text rendering, refresh filtering, deduplication, or a different active space on this phone. The likely shared cause is that production has not yet received the verified token-independent membership server change: production also omits this tokenless Android self. Fixing the durable roster requires deploying that server change and allowing both Android devices to re-register; this diagnostic task did not write server data or deploy. Whether 김부장의 own phone currently selected a different space cannot be read while `R3CX10FTE1L` remains disconnected, but its same-space received events prove it used the active space recently.

## 13:25 KST addendum — 김부장 voice reaches iPhone but not Android

The differential is transport routing, not Android audio decode/playback. SM_F968N history now contains seven recent same-space 김부장 events—three ding-dong and four quiet alerts—but zero voice events. Therefore the voice event never entered Android history, dedup, mute, file storage, or playback handling.

The path evidence is consistent and complete:

- `AndroidBleTransport.send` rejects `VoiceMessage`; BLE carries the small ding/quiet events that SM_F968N continues to receive.
- Production `/members` omits SM_F968N, so server broadcast/target resolution has no durable Android recipient.
- Production per-device inbox returned HTTP 404, confirming the verified inbox fallback is not deployed there.
- SM_F968N logcat reports `Default FirebaseApp failed to initialize because no default options were found`; no `google-services.json` or Apple-equivalent Firebase config exists in either client repository. Thus FCM cannot provide the missing remote voice route.
- There was no voice/media exception after the reported send, because no voice event arrived. The audio subsystem itself initialized normally.
- `CallEventCodingTest` 8/8 proves voice kind, base64 bytes, size validation, target fields, and round-trip integrity. `CallHistoryStoreTest` 8/8 proves received voice bytes persist and reload for replay. These 16 focused tests passed unchanged.

iPhone receives the same voice because its APNs-backed server membership exists. Android receives ding-dong because BLE remains available. A safe Android-only code edit cannot create a server recipient or inbox endpoint, and sending up to 2 MiB voice over the 160-byte BLE framing path would violate the established transport safety constraint. The correct fix is deployment of the already verified token-independent membership + inbox server change, followed by Android re-registration; alternatively Android FCM requires valid Firebase project configuration. Both are outside this no-server-deploy/no-security-change diagnostic scope. No Android source was changed for this addendum, and ding-dong behavior was left untouched.

## 13:43 KST addendum — superseding Android BLE direction and voice diagnosis

The 13:25 conclusion is superseded for current two-Android proximity behavior. Once both phones were reachable, their persisted stable device IDs exactly matched the opposite roster targets. The actual defect was BLE connection-direction asymmetry: `AndroidBleTransport` counted both subscribed centrals and client-side central GATT connections as live, but transmitted only with GATT-server notifications to subscribed centrals. Depending on connection order and reconnect state, one direction therefore had a displayed live route with no outbound write. Broadcasts could appear less affected through another relay, while selected targets exposed the dropped direction.

Android now selects exactly one route per BLE address: server notification for subscribed peers, otherwise client characteristic write for connected peers. The same encrypted payload carries the canonical authenticated `senderID`; no member identity is inferred from a name, BLE address, or per-event UUID. The GATT characteristic accepts both notification and write paths. Voice uses the same target routing with a bounded 64 KiB BLE fallback; larger voice stays in the durable backend outbox/inbox path. Authenticated same-space inbound senders persist as local known members by stable deviceID and later authoritative `/members` rows reconcile that same ID.

### Final validation

| Check | Result |
|---|---|
| Static/diff | `git diff --check` passed; ledger JSON parsed successfully |
| Focused JVM | 33/33 passed: BLE codec/routes, event coding, history, member consistency, space lifecycle, backend client |
| Build | `./gradlew assembleDebug` passed; APK SHA-256 `3552523f458fb766a813576cdd3df5f1f63df31757c82bde5101e4f67fe16f17` |
| Install | `install -r` passed on `SM-S928N` and `SM_F968N`; pre/post app-state aggregate hashes were identical (`e4898d…` and `4b02ea…` respectively); both launched as versionName 2.0.1/versionCode 340680 |
| Roster UI | Both phones showed `구성원 3명` with `김부장`, `대표`, `대표 아이폰`; each card used visible name then one `역할 · 알림 · 상태` line |
| 김부장 → 대표 selected, before reconnect | 톡톡 PASS `82b96712…`; 띵동 PASS `0ab43e5e…`; voice PASS `cd65e637…`, same receiver eventID and persisted nonempty M4A |
| 대표 → 김부장 selected, before reconnect | 톡톡 PASS `ae86a617…`; 띵동 PASS `1df08002…`; voice PASS `3628526a…`, same receiver eventID and persisted M4A |
| Reverse-order relaunch/reconnect | Both phones returned to `근처 2대`, `구성원 3명` |
| 김부장 → 대표 selected, after reconnect | 톡톡 PASS `7b7520ac…`; 띵동 PASS `f47f1ef2…`; voice PASS `d0ebb704…` |
| 대표 → 김부장 selected, after reconnect | 톡톡 PASS `9adedbb4…`; 띵동 PASS `c2f960b6…`; voice PASS `d9428adf…` |
| Broadcast regression after reconnect | 김부장 broadcast 톡톡 PASS `d0a1a675…`; broadcast voice PASS `d189dc03…` on representative Android |

Evidence manifests:

- SM-S928N roster: `.sync/evidence/kim-members-final.png`, SHA-256 `432425be26b1562be201cbf87bfc377f7130279eeaafaf3db4b08ffcb78da54`.
- SM_F968N roster: `.sync/evidence/rep-members-final.png`, SHA-256 `9df27debd39a1a4fa3754647e7993920c9ca0a194215d15b41f15251a6a0ad03`.
- SM_F968N selected voice receipt: `.sync/evidence/rep-selected-voice-received.png`, SHA-256 `19cb49f140b8886e9bf56bd4f0ed025205595f20984e4ad6c0fff9001d1cf583`.

The final reconnect validation completed before the SM-S928N wireless-debug ADB endpoint subsequently became offline; this does not invalidate the matching sender/receiver history captured from both devices, but any additional inspection requires reopening wireless debugging if Android has rotated the endpoint. No app data was cleared, no server was deployed, and no Git/version/release/site action was performed. Production delivery for devices beyond BLE range and voice over 64 KiB remains dependent on deployment of the already verified membership/inbox server contract; tablet/TV rendered evidence also remains open.

Final matchup validator: `python3 /Users/armsone/.codex/skills/matchup/scripts/validate_parity_ledger.py .parity/ledger.json --gate` returned exit 3: `Structure OK: 11 row(s), 0 complete, 11 open`, followed by `GATE FAILURE: 11 blocking item(s)`. The report therefore does not claim full cross-platform synchronization.

## 13:55 KST addendum — realtime member state, pull refresh, and finish-work

Durable member cards now recompose immediately from authenticated BLE peer identity changes: a disconnect removes only that stable deviceID from the live set and changes `근처 연결됨` to `공간에 등록됨`; it never removes the card. Aggregate transport loss clears all remote live flags. Backend member refresh reconciles role/name/mute by stable deviceID, current-device mute WorkManager outcomes update `동기화 중/필요`, and stale callbacks from another space are rejected.

Phone/tablet home now uses Compose pull-to-refresh. Resume triggers an immediate refresh and starts a 30-second visible cadence; background/dispose cancels it. Pull, resume, and periodic requests coalesce per active space, old-space completion cannot end the new spinner, and the spinner has a 10-second bound. One refresh triggers membership registration, authoritative members, BLE connection refresh, inbox, and outbox recovery. Google TV keeps the same data path through a focusable full-width `새로고침` action.

Finish-work validation and installation:

- Disk gate: 248,877,000 KiB available (>50 GiB and >10%).
- Android phone/tablet/Google TV configuration: touchscreen/camera/microphone/BLE remain optional, LEANBACK launcher remains present, adaptive card columns and TV D-pad refresh action are source-verified.
- Focused JVM suite: 56/56 passed across member/space lifecycle, BLE routing, voice coding/history, backend/inbox, and update policy. Final directly affected rerun: 29/29 passed.
- `assembleDebug`: passed. Final APK SHA-256 `4026db0d03836eed370910154172567910113839c98755a9b71c7073e5a8c81b`.
- `SM_F968N`: existing app force-stopped, final APK `install -r` succeeded, aggregate app-data hash remained `89319ef4…`, launch succeeded, and UI hierarchy showed `근처 2대`, `구성원 3명`, `김부장`, `대표`, `대표 아이폰` with two-row statuses.
- `SM-S928N`/`R3CX10FTE1L`: the immediately preceding BLE-direction build was installed and passed the full bidirectional/reconnect 12-path matrix, but its wireless-debug endpoint disappeared from both ADB and mDNS before this final pull-refresh APK. It remains visible to SM_F968N over BLE. Installing the last APK is unfinished until Wireless debugging is reopened and a new endpoint appears.
- No emulator, uninstall, clear-data, version bump, external release, server deploy, or site publication was performed.
