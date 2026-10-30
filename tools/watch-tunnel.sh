#!/usr/bin/env bash
# adb to the watch through the phone, so the watch stays on the wrist:
#   Mac adb --USB--> phone adbd --adb forward--> AAPS AdbTunnelServer (127.0.0.1:4444)
#     --Wear channel over Bluetooth--> AAPS Wear AdbTunnelService --> 127.0.0.1:5555 = the watch's adbd
#
#   tools/watch-tunnel.sh up        # open; afterwards: adb -s 127.0.0.1:4444 install -r x.apk / shell / logcat ...
#   tools/watch-tunnel.sh down      # close
#   tools/watch-tunnel.sh status
#   tools/watch-tunnel.sh bootstrap # ONCE PER WATCH BOOT, watch on USB: make its adbd listen on TCP (adb tcpip 5555)
#
# Debuggable AAPS builds only (phone and watch). Env: PHONE_SERIAL, WATCH_SERIAL (else detected by
# ro.build.characteristics), AAPS_PACKAGE, TUNNEL_PORT (4444), WATCH_ADB_PORT (5555), TUNNEL_IDLE_MIN (120).
set -euo pipefail
PKG=${AAPS_PACKAGE:-info.nightscout.androidaps}
LISTEN=${TUNNEL_PORT:-4444}
WATCH_PORT=${WATCH_ADB_PORT:-5555}
IDLE_MIN=${TUNNEL_IDLE_MIN:-120}
TUNNEL="127.0.0.1:$LISTEN"
RECEIVER="$PKG/app.aaps.pump.combowatch.AdbTunnelReceiver"

devices() { adb devices | awk 'NR>1 && $2=="device" {print $1}' | grep -v '^127\.0\.0\.1:' || true; }
is_watch() { adb -s "$1" shell getprop ro.build.characteristics 2>/dev/null | tr -d '\r' | grep -q watch; }
phone() {
  if [ -n "${PHONE_SERIAL:-}" ]; then echo "$PHONE_SERIAL"; return; fi
  local d found=""
  for d in $(devices); do is_watch "$d" || found="$found $d"; done
  set -- $found
  [ $# -eq 1 ] || { echo "cannot tell the phone apart, set PHONE_SERIAL (candidates:$found)" >&2; exit 2; }
  echo "$1"
}
watch_usb() {
  if [ -n "${WATCH_SERIAL:-}" ]; then echo "$WATCH_SERIAL"; return; fi
  local d
  for d in $(devices); do if is_watch "$d"; then echo "$d"; return; fi; done
  echo "watch is not on USB" >&2; exit 2
}
status_file() { adb -s "$1" shell "run-as $PKG cat files/combowatch-adb-tunnel.json 2>/dev/null" | tr -d '\r'; }

cmd=${1:-up}
case "$cmd" in
  up)
    P=$(phone)
    adb -s "$P" shell am broadcast -n "$RECEIVER" --es cmd start --ei listen "$LISTEN" --ei port "$WATCH_PORT" --ei idle "$IDLE_MIN" >/dev/null
    s=""
    for _ in $(seq 1 12); do s=$(status_file "$P"); echo "$s" | grep -q '"running":true' && break; sleep 0.5; done
    echo "phone: ${s:-no answer from AAPS (debug build with the tunnel installed?)}"
    echo "$s" | grep -q '"running":true' || exit 3
    adb -s "$P" forward "tcp:$LISTEN" "tcp:$LISTEN" >/dev/null
    adb connect "$TUNNEL" >/dev/null || true
    st=""
    for _ in $(seq 1 30); do st=$(adb devices | awk -v t="$TUNNEL" '$1==t {print $2}'); [ "$st" = "device" ] && break; sleep 1; done
    if [ "$st" = "device" ]; then
      echo "watch: $(adb -s "$TUNNEL" shell getprop ro.product.model 2>/dev/null | tr -d '\r') via $TUNNEL"
    else
      echo "watch: ${st:-no answer}. Is the watch near the phone, its adbd on tcp $WATCH_PORT (bootstrap after a reboot), the new wear app installed?"
      echo "phone: $(status_file "$P")"
      exit 4
    fi ;;
  down)
    P=$(phone)
    adb disconnect "$TUNNEL" >/dev/null 2>&1 || true
    adb -s "$P" forward --remove "tcp:$LISTEN" >/dev/null 2>&1 || true
    adb -s "$P" shell am broadcast -n "$RECEIVER" --es cmd stop >/dev/null
    sleep 1; echo "phone: $(status_file "$P")" ;;
  status)
    P=$(phone)
    echo "phone: $(status_file "$P")"
    adb devices | grep "$TUNNEL" || echo "no adb connection to $TUNNEL" ;;
  bootstrap)
    W=$(watch_usb)
    adb -s "$W" tcpip "$WATCH_PORT"
    echo "watch $W: adbd listens on tcp $WATCH_PORT until the watch reboots" ;;
  *)
    echo "usage: $0 up|down|status|bootstrap" >&2; exit 1 ;;
esac
