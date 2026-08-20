#!/bin/zsh
set -euo pipefail

IOS_SIMULATOR_UDID=${IOS_SIMULATOR_UDID:?Set IOS_SIMULATOR_UDID to a dedicated simulator}
IOS_APP=${IOS_APP:-/private/tmp/button-matchup-ios-derived/Build/Products/Debug-iphonesimulator/iOS.app}
OUTPUT_DIR=${1:-docs/screenshots/ios}

mkdir -p "$OUTPUT_DIR"
xcrun simctl bootstatus "$IOS_SIMULATOR_UDID" -b
xcrun simctl install "$IOS_SIMULATOR_UDID" "$IOS_APP"
xcrun simctl status_bar "$IOS_SIMULATOR_UDID" override --time 9:41 \
  --batteryState charged --batteryLevel 100 --wifiBars 3 --cellularBars 4
xcrun simctl launch "$IOS_SIMULATOR_UDID" com.armsone.button
xcrun simctl io "$IOS_SIMULATOR_UDID" screenshot --type=png "$OUTPUT_DIR/setup_welcome.png"
shasum -a 256 "$OUTPUT_DIR/setup_welcome.png"

echo "The iOS source has no launch fixture catalog. Only the clean setup welcome baseline is deterministic."
