#!/usr/bin/env bash
cd "/Users/alexeydedeshko/StudioProjects/OpenApsAIMI-additional-sport-options" || exit 1
./scripts/aaps_adb_wifi_connect.sh
printf '\nDone. You can close this window.\n'
read -r -p "Press Enter to close..."
