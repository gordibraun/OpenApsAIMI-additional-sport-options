#!/usr/bin/env bash
set -euo pipefail

SERIAL="${AAPS_SERIAL:-RRCX807YMBY}"
LABEL="com.aaps.phone-collector"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLIST_SRC="$SCRIPT_DIR/$LABEL.plist"
PLIST_DST="$HOME/Library/LaunchAgents/$LABEL.plist"
STATE_DIR="$HOME/.aaps-phone-collector"
STATE_FILE="$STATE_DIR/${SERIAL}.connected"
ADB="${ADB:-/Users/alexeydedeshko/Library/Android/sdk/platform-tools/adb}"

mkdir -p "$HOME/Library/LaunchAgents" "$STATE_DIR"
cp "$PLIST_SRC" "$PLIST_DST"

# Avoid an immediate duplicate export if the phone is already connected at install time.
if "$ADB" devices | awk -v serial="$SERIAL" '$1 == serial && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
  date '+%Y-%m-%d %H:%M:%S %z' > "$STATE_FILE"
else
  rm -f "$STATE_FILE"
fi

launchctl bootout "gui/$(id -u)" "$PLIST_DST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST_DST"
launchctl enable "gui/$(id -u)/$LABEL"

echo "Installed and started $LABEL"
echo "Logs: $STATE_DIR/watch.log"
