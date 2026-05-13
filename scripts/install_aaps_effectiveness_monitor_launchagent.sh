#!/usr/bin/env bash
set -euo pipefail

LABEL="com.aaps.effectiveness-monitor"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLIST_SRC="$SCRIPT_DIR/$LABEL.plist"
PLIST_DST="$HOME/Library/LaunchAgents/$LABEL.plist"
STATE_DIR="$HOME/.aaps-effectiveness-monitor"

mkdir -p "$HOME/Library/LaunchAgents" "$STATE_DIR"
cp "$PLIST_SRC" "$PLIST_DST"

launchctl bootout "gui/$(id -u)" "$PLIST_DST" >/dev/null 2>&1 || true
launchctl bootstrap "gui/$(id -u)" "$PLIST_DST"
launchctl enable "gui/$(id -u)/$LABEL"

echo "Installed and started $LABEL"
echo "Logs: $STATE_DIR/monitor.log"
echo "Reports: $(cd "$SCRIPT_DIR/.." && pwd)/aaps-effectiveness-monitor/latest_report.md"
