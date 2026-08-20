# iOS → Android matchup matrix

Evidence status: **latest iOS source contract and Android phone/tablet runtime catalogs verified; full paired visual parity remains unverified**. The iOS Swift sources were re-inspected on 2026-08-21 after build 13 added one-recipient calls and call-activity banners. Android unit tests, debug/release builds, installation, and cold launch passed, and 32 deterministic states were captured on both physical Android devices. The iOS project still has no fixture/XCUITest catalog, so no row is labelled fully `matched`.

Shared profile is `ko-KR`, light appearance, portrait, default text scale, a deterministic family named `우리 가족`, sender `엄마`, member `첫째`, unless a row states otherwise. Android captures are 822×1918 PNGs from SM-F968N (Android 16) and 1200×2000 PNGs from SM-T500 (Android 10). The only available iOS baseline is 1206×2622 from the dedicated iPhone simulator. Immutable hashes are recorded in `docs/screenshots/SHA256SUMS`.

| Route/state ID | Element/anatomy | Dimension/action | Fixture/profile | iOS exact reference | Android observed | Difference | Required action | Evidence/confidence | Status/exception proof |
|---|---|---|---|---|---|---|---|---|---|
| setup_welcome | two setup rows | content, order, navigation | clean install | `SetupWelcomeView.swift`: `새 가족 공간 만들기`, then `초대 QR로 참여하기` | Compose source targets same routes | runtime geometry unmeasured | paired phone/tablet capture and taps | source/High | implemented from source; visual parity unverified |
| create_default_invalid | create form | default values and disabled CTA | clean install | `CreateSpaceView.swift`: `우리 가족`, empty member, disabled `공간 만들기` | source target inventoried | runtime disabled styling unmeasured | capture and accessibility state trace | source/High | implemented from source; visual parity unverified |
| create_filled_valid | create form | validation and transition | space=`우리 가족`, member=`엄마` | both trimmed nonempty; CTA enters role selection | source target inventoried | behavioral trace missing | enter values, tap, relaunch persistence check | source/High | implemented from source; behavioral parity unverified |
| join_camera | QR scanner | camera preview and instruction | physical phone, permission allowed | 300pt preview; `가족 기기의 초대 QR 코드를 화면에 맞춰 주세요.` | CameraX source target | camera geometry unmeasured | paired physical-device capture | source/Medium | 확인 필요 |
| join_camera_denied | scanner message | permission failure | camera denied | `설정 앱에서 카메라 접근을 허용해 주세요.` | platform permission flow differs | app-owned fallback not captured | capture after denial | source/High | implemented from source; OS dialog itself is exception candidate only |
| join_no_camera | manual fallback card | simulator/no camera | iOS simulator | exact two-line `시뮬레이터에서는…` message | Android emulator path not proven | device capability paths differ | deterministic no-camera fixture | source/High | 확인 필요 |
| join_manual_empty | manual input | disabled confirmation | empty link | placeholder `buttonapp://invite/v1?...`; disabled `확인` | source target inventoried | runtime text baseline unmeasured | capture empty field | source/High | implemented from source; visual parity unverified |
| join_manual_invalid | validation error | exact error and recovery | `https://invalid` | `버튼 앱의 초대 코드가 아니에요.` with warning | source target inventoried | runtime trace missing | enter invalid link and capture | source/High | implemented from source; behavioral parity unverified |
| join_invite_confirmed_empty | confirmed invite card | content and disabled CTA | valid invite, no member name | seal icon; `“우리 가족” 공간 초대를 확인했어요.` | source target inventoried | icon geometry unmeasured | capture valid parse | source/High | implemented from source; visual parity unverified |
| join_invite_confirmed_named | participate action | enabled CTA and persistence | valid invite, member=`첫째` | `참여하기`; then role selection | source target inventoried | relaunch trace missing | tap and relaunch | source/High | implemented from source; behavioral parity unverified |
| role_compact | parent/child cards | vertical layout and exact text | compact width | `RoleSelectionView.swift`, parent then child | Compose compact target | bounds unmeasured | phone capture | source/High | implemented from source; visual parity unverified |
| role_regular | parent/child cards | horizontal adaptive layout | regular/≥600dp | iOS HStack for regular size class | Android tablet target | breakpoint equivalence unproven | iPad + Android tablet pair | source/High | 확인 필요 |
| parent_idle | action cards | content/order | parent, searching | quiet, ding, voice; family list; header | source target inventoried | runtime layout unmeasured | deterministic capture | source/High | implemented from source; visual parity unverified |
| parent_connected | status badge | state text | 1 peer | `근처 기기 1대와 연결됨` | transport contract supplies same text | BLE runtime not traced | paired connection trace | source/High | implemented from source; behavioral parity unverified |
| parent_demo | demo hint | content/state | demo enabled | `데모 모드: 보낸 호출이 이 기기로 다시 전달돼요.` | source target inventoried | runtime not captured | toggle demo, return home | source/High | implemented from source; visual parity unverified |
| parent_targeted | family presence list | choose one recipient | member=`첫째` | selected row checkmark; `선택한 한 사람에게만 보내요.` | phone/tablet fixtures show the selected member and exact guidance | paired iOS fixture absent | add iOS deterministic fixture | runtime/High | Android runtime verified; paired visual parity unverified |
| parent_sent | call activity banner | targeted send feedback | recipient=`첫째`, ding | `첫째님에게 띵동 호출을 보냈어요.` with sent icon | exact message and dismiss control captured on phone/tablet | icon is platform-rendered approximation | add paired iOS fixture | runtime/High | Android runtime verified |
| parent_ack | acknowledge banner | content/dismiss | ack sender=`첫째` | `첫째님이 호출을 확인했어요.` and close button | source target inventoried | dismissal trace missing | inject ack and dismiss | source/High | implemented from source; behavioral parity unverified |
| parent_quiet_tap | quiet action | one call | parent home | tap sends quiet unless hold suppresses tap | source target inventoried | gesture trace missing | tap once; assert one event | source/High | implemented from source; behavioral parity unverified |
| parent_quiet_hold | quiet action | threshold + 5s siren, tap suppression | press and hold | after a 5s hold, siren plays for 5s; release cancels only a pending threshold; tap suppressed after trigger | Android player synthesizes 5s siren | gesture integration unverified | threshold/audio/gesture trace | source/High | implemented from source; behavioral parity unverified |
| parent_ding_tap | ding action | E5→C5 x3 send | parent home | sends ding event and haptic | Android player synthesis implemented | exact PCM output not measured | waveform/hash fixture and event trace | source/High | implemented from source; audio parity unverified |
| invite_qr | QR card/share | QR payload and actions | family=`우리 가족` | 280pt QR, `초대 링크 공유`, `닫기` | source target inventoried | QR raster and share sheet differ | decode both QR images | source/High | app surface unverified; system share sheet exception candidate |
| child_idle | waiting card | content/order | child, searching | exact waiting title/body, preview button | source target inventoried | runtime geometry unmeasured | phone capture | source/High | implemented from source; visual parity unverified |
| child_connected | status/icon | transition | child, 1 peer | connected badge; one bounce trigger | transport contract supplied | animation trace missing | record transition | source/High | implemented from source; behavioral parity unverified |
| child_demo | demo hint | exact content | demo enabled | `데모 모드: 보낸 호출이 이 기기로 돌아와요.` | source target inventoried | runtime unmeasured | capture | source/High | implemented from source; visual parity unverified |
| child_targeted | family presence list | choose one recipient | member=`첫째` | same selection contract as parent | exact selected state captured on phone/tablet | paired iOS fixture absent | add iOS deterministic fixture | runtime/High | Android runtime verified |
| child_sent | call activity banner | broadcast send feedback | quiet call | `모두에게 조용한 호출을 보냈어요.` | exact message captured on phone/tablet | paired iOS fixture absent | add iOS deterministic fixture | runtime/High | Android runtime verified |
| child_ack | call activity banner | correlated ACK feedback | ack sender=`첫째` | `첫째님이 호출을 확인했어요.` | exact message captured on phone/tablet; Android accepts only known `ackFor` IDs | real two-device ACK trace pending | cross-platform event trace | source+runtime/High | UI verified; transport trace pending |
| incoming_quiet | incoming sheet | icon/title/ack | sender=`엄마` | `엄마님의 조용한 호출`, quiet bell, `확인했어요` | source target inventoried | time and sheet geometry unmeasured | fixed clock capture + ack trace | source/High | implemented from source; visual parity unverified |
| incoming_ding | incoming sheet | sound/flash/title | sender=`엄마` | `엄마님의 띵동 호출`; ding/flash side effects | Android synthesis and torch implemented | side effects not measured | event injection, audio/torch trace | source/High | implemented from source; behavioral parity unverified |
| incoming_voice | incoming sheet | playback action | valid M4A | orange mic and `음성 메시지 듣기` | Android player implemented | playback trace missing | fixed M4A fixture | source/High | implemented from source; behavioral parity unverified |
| voice_idle | recorder sheet | idle anatomy/text | permission granted | exact instruction, blue mic, max 15s status | recorder API/source target | runtime unmeasured | capture | source/High | implemented from source; visual parity unverified |
| voice_permission_requesting | recorder status | permission race | undetermined, held | `마이크 권한을 확인하는 중…`; pulse | generation/held guard implemented | OS dialog differs | release before response test + capture | source/High | app state implemented; OS dialog exception candidate |
| voice_permission_denied | recorder status | denied recovery | permission denied | gray mic, `마이크 접근이 꺼져 있어요.`, settings button | source target inventoried | settings destination untraced | deny and tap settings | source/High | implemented from source; OS settings screen exception candidate |
| voice_recording | recorder status | press/hold | granted, held | red waveform, `녹음 중… 손을 떼면 전송돼요.` | AAC/M4A 22.05kHz mono recorder implemented | waveform/gesture unverified | hold capture and file probe | source/High | implemented from source; behavioral parity unverified |
| voice_auto_stop | recorder limit | 15-second auto completion | held ≥15s | auto-stop at 15s and send | 15,000ms handler + recorder cap | timing not measured | monotonic timing test | source/High | implemented from source; behavioral parity unverified |
| voice_sent | recorder feedback | completion/reset | valid recording | `전송했어요` for ~2 seconds | source target inventoried | duration unverified | release and capture | source/High | implemented from source; behavioral parity unverified |
| settings_notification_needed | notification row | permission CTA | not determined | `허용 필요`, `잠금화면 알림 허용` | Android permission target | OS authorization mechanics differ | Android 13+ capture | source/High | app surface unverified; OS dialog exception candidate |
| settings_notification_denied | notification row | denied CTA | denied | red `차단됨`, iOS settings action | Android correctly uses `Android 알림 설정 열기` | OS destination naming is platform-owned content | paired capture with smallest affected mask | runtime/High | Android captured; platform exception documented |
| settings_notification_allowed | notification row | allowed state | authorized | `허용됨`, no permission CTA | source target inventoried | runtime unmeasured | capture | source/High | implemented from source; visual parity unverified |
| settings_demo_on | demo toggle | persistence/transport | toggle on | transport becomes `데모 모드`; call loops back | source target inventoried | relaunch trace missing | toggle, call, relaunch | source/High | implemented from source; behavioral parity unverified |
| settings_push_not_requested | remote call section | status/action | no provider/token | iOS: `알림 권한을 아직 요청하지 않음`, `원격 알림 켜기` | push interface only; no Firebase bound | provider absent by design | integrate provider or mark product gap | source/High | remaining |
| settings_push_waiting | remote call section | intermediate state | token requested | `APNs 토큰 발급 대기 중` | no Android provider bound | missing implementation binding | provider integration | source/High | remaining |
| settings_backend_offline | server row | exact state | no URL | `구성되지 않음 (오프라인)` in orange | BackendConfiguration implemented | UI runtime unmeasured | capture | source/High | implemented from source; visual parity unverified |
| settings_backend_configured | server row | URL rendering | fixed HTTPS URL | exact absolute URL, secondary color | HTTP client/config implemented | network behavior untraced | fixture URL capture and mock server | source/High | implemented from source; behavioral parity unverified |
| settings_leave_confirm | destructive dialog | wording/actions | joined family | exact title, destructive `공간 나가기`, cancel | source target inventoried | platform dialog pixels app-owned and unmeasured | capture and cancel/destructive traces | source/High | implemented from source; visual parity unverified |
| settings_role_reset | role reset | retained space | tap `역할 다시 고르기` | returns role selection, preserves family/name | source target inventoried | relaunch trace missing | tap, select, relaunch | source/High | implemented from source; behavioral parity unverified |
| root_send_error | error alert | exact wording/recovery | no peer/server | title `전송 안내`, `확인`, model error | source target inventoried | error variants untraced | inject each transport/backend error | source/High | implemented from source; behavioral parity unverified |
| widget_voice_entry | voice sheet | widget entry | configured family | widget URL opens same VoiceMessageView | Android parent/child 2×2 widgets dispatch quiet/ding/voice/open into MainActivity | launcher-host pixels and cold-start trace not captured | widget-host behavior trace | source/High | implemented; host verification pending |
| parent_tablet_landscape | home adaptive container | large-screen geometry | tablet landscape | max-width adaptive SwiftUI container | ≥600dp Android layout target | paired bounds absent | iPad/Android tablet landscape capture | source/Medium | 확인 필요 |
| child_large_text | waiting card | accessibility wrapping | largest supported text | SwiftUI text wraps; actions remain available | Compose target | clipping audit absent | paired large-text capture + semantics | source/Medium | 확인 필요 |

## Regeneration and capture

Android debug builds expose deterministic fixture IDs and the capture script preserves the original PNGs. Regenerate the Android catalog with:

```bash
ANDROID_SERIAL=<physical-device-serial> scripts/capture-android-catalog.sh docs/screenshots/android-phone

# iOS welcome baseline only; other states require an iOS fixture catalog first
scripts/capture-ios-baseline.sh <simulator-udid>

# Record immutable hashes without rewriting captures
shasum -a 256 docs/screenshots/ios/*.png docs/screenshots/android-phone/*.png docs/screenshots/android-tablet/*.png
```

Do not normalize by independently scaling screenshots. Record device, OS, orientation, density, app bounds/insets, locale, theme, font scale, fixture hash, revision/dirty state, and masked OS-owned regions before comparison.

## Forced OS exception candidates (not yet proven exceptions)

- Runtime permission dialogs, system Settings destinations, keyboard, share sheet, system bars and system navigation gestures.
- Android notification-channel settings and system-rendered notification chrome.
- Background BLE behavior after process death, force-stop, Doze, OEM power restrictions, and missing foreground-service/user-visible notification. The implementation promises only lifecycle-safe foreground best effort.

Each candidate remains `확인 필요` until captures document the exact affected region/behavior, OS ownership, public-API limitation, unavoidability, and smallest valid mask.
