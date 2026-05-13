#!/usr/bin/env bash
set -euo pipefail

SOUND="${CODEX_DONE_SOUND:-/System/Library/Sounds/Glass.aiff}"

if command -v afplay >/dev/null 2>&1 && [ -f "$SOUND" ]; then
  afplay "$SOUND"
elif command -v osascript >/dev/null 2>&1; then
  osascript -e 'beep 2'
else
  printf '\a'
fi
