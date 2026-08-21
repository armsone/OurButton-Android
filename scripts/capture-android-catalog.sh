#!/bin/zsh
set -euo pipefail

ADB_BIN=${ADB_BIN:-adb}
ANDROID_SERIAL=${ANDROID_SERIAL:?Set ANDROID_SERIAL to the dedicated emulator serial}
OUTPUT_DIR=${1:-docs/screenshots/android}
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}
display_args=()
if [[ -n "${ANDROID_DISPLAY_ID:-}" ]]; then
  display_args=(-d "$ANDROID_DISPLAY_ID")
fi

if [[ -n "${BUTTON_FIXTURES:-}" ]]; then
  fixtures=(${=BUTTON_FIXTURES})
else
  fixtures=(
    setup_welcome setup_create setup_join setup_join_invalid setup_join_confirmed
    role_selection parent_home parent_home_targeted parent_home_sent parent_home_ack parent_home_history parent_home_demo parent_home_idle
    parent_home_searching child_home child_home_targeted child_home_sent child_home_ack child_home_history child_home_demo invite_qr voice_idle
    voice_requesting voice_recording voice_denied voice_sent incoming_quiet
    incoming_dingdong incoming_voice settings settings_notification_denied
    settings_notification_allowed settings_remote_configured global_error
  )
fi

mkdir -p "$OUTPUT_DIR"
"$ADB_BIN" -s "$ANDROID_SERIAL" install -r "$APK" >/dev/null

for fixture in $fixtures; do
  "$ADB_BIN" -s "$ANDROID_SERIAL" shell am force-stop com.armsone.button
  "$ADB_BIN" -s "$ANDROID_SERIAL" shell am start -W -n com.armsone.button/.MainActivity \
    --es button_fixture "$fixture" >/dev/null
  sleep 0.7
  remote="/sdcard/button_${fixture}.png"
  "$ADB_BIN" -s "$ANDROID_SERIAL" shell screencap $display_args -p "$remote"
  "$ADB_BIN" -s "$ANDROID_SERIAL" pull "$remote" "$OUTPUT_DIR/${fixture}.png" >/dev/null
done

shasum -a 256 "$OUTPUT_DIR"/*.png
