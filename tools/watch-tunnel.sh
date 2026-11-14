#!/usr/bin/env bash
# adb to the watch through the phone, so the watch stays on the wrist:
#   Mac adb --USB--> phone adbd --adb forward--> AAPS AdbTunnelServer (127.0.0.1:4444)
#     --Wear channel over Bluetooth--> AAPS Wear AdbTunnelService --> 127.0.0.1:5555 = the watch's adbd
#
#   tools/watch-tunnel.sh up            # open; afterwards: adb -s 127.0.0.1:4444 shell / logcat / pull ...
#   tools/watch-tunnel.sh install x.apk # an APK onto the watch through the open tunnel (compressed push, then pm install)
#   tools/watch-tunnel.sh down          # close - always, when the work is done: left up for a night it took the phone's AAPS down (05.11.2026)
#   tools/watch-tunnel.sh status
#   tools/watch-tunnel.sh bootstrap     # ONCE PER WATCH BOOT, watch on USB: make its adbd listen on TCP (adb tcpip 5555);
#                                       # the Mac's phone-link agent does this by itself whenever the watch sits on the Mac's dock
#
# Debuggable AAPS builds only (phone and watch). Env: PHONE_SERIAL, WATCH_SERIAL (else detected by
# ro.build.characteristics), AAPS_PACKAGE, TUNNEL_PORT (4444), WATCH_ADB_PORT (5555), TUNNEL_IDLE_MIN (120).
# Speed is what Bluetooth gives: ~0.5 MB/s on 30.10.2026, ~0.1 MB/s on 14.11.2026. The debug APKs keep
# their code uncompressed (bench 28 MB, wear 48 MB), so `install` pushes them compressed: about a third.
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
  local d found="" usb="" sn sn0=""
  for d in $(devices); do is_watch "$d" || found="$found $d"; done
  set -- $found
  [ $# -ge 1 ] || { echo "no phone attached to adb (cable, or Wi-Fi on the Mac's network)" >&2; exit 2; }
  # One phone seen twice - on the cable and over Wi-Fi - is one phone; the cable is preferred.
  for d in "$@"; do
    sn=$(adb -s "$d" shell getprop ro.serialno 2>/dev/null | tr -d '\r')
    [ -n "$sn0" ] || sn0="$sn"
    [ "$sn" = "$sn0" ] || { echo "cannot tell the phone apart, set PHONE_SERIAL (candidates:$found)" >&2; exit 2; }
    case "$d" in *:*) ;; *) [ -n "$usb" ] || usb="$d" ;; esac
  done
  echo "${usb:-$1}"
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
      echo "watch: ${st:-no answer}"
      # Which end is dead, by what the phone's side saw: no watch node at all, or a watch that answers nothing.
      why=$(status_file "$P")
      case "$why" in
        *"no watch connected"*) echo "  the phone has no watch connected: Bluetooth off on either, or the watch away / switched off" ;;
        *"answers nothing"*)    echo "  the watch is connected to the phone, but its adbd does not listen on tcp $WATCH_PORT: it has rebooted since"
                                echo "  the last bootstrap. A minute on the Mac's dock cures it (the phone-link agent runs adb tcpip by itself)." ;;
        *)                      echo "  phone says: ${why:-nothing}. The watch near the phone? its adbd on tcp $WATCH_PORT? the wear app with the tunnel installed?" ;;
      esac
      # Never leave adb retrying against a dead end: 05.11.2026 it reconnected 4-5 times a second for hours,
      # each attempt opening a Wear channel on the phone, until AAPS there went silent.
      adb disconnect "$TUNNEL" >/dev/null 2>&1 || true
      adb -s "$P" forward --remove "tcp:$LISTEN" >/dev/null 2>&1 || true
      adb -s "$P" shell am broadcast -n "$RECEIVER" --es cmd stop >/dev/null 2>&1 || true
      echo "phone: tunnel stopped again (watch unreachable)"
      exit 4
    fi ;;
  install)
    APK=${2:-}
    [ -f "$APK" ] || { echo "usage: $0 install <apk>   (no such file: ${APK:-nothing given})" >&2; exit 1; }
    [ "$(adb devices | awk -v t="$TUNNEL" '$1==t {print $2}')" = "device" ] || { echo "the tunnel is not up: $0 up" >&2; exit 3; }
    REMOTE="/data/local/tmp/tunnel-install-$$.apk"
    echo "pushing $(du -h "$APK" | awk '{print $1}') to the watch through the phone, compressed ..."
    adb -s "$TUNNEL" push -z brotli "$APK" "$REMOTE"
    # The app's process dies here and is not started again by this script: start it, and check it, yourself.
    adb -s "$TUNNEL" shell "pm install -r $REMOTE; rm -f $REMOTE" ;;
  down)
    P=$(phone)
    adb disconnect "$TUNNEL" >/dev/null 2>&1 || true
    adb -s "$P" forward --remove "tcp:$LISTEN" >/dev/null 2>&1 || true
    adb -s "$P" shell am broadcast -n "$RECEIVER" --es cmd stop >/dev/null
    sleep 1; echo "phone: $(status_file "$P")" ;;
  status)
    P=$(phone)
    adb -s "$P" shell am broadcast -n "$RECEIVER" --es cmd status >/dev/null 2>&1 || true
    sleep 1
    echo "phone: $(status_file "$P")"
    adb devices | grep "$TUNNEL" || echo "no adb connection to $TUNNEL" ;;
  bootstrap)
    W=$(watch_usb)
    adb -s "$W" tcpip "$WATCH_PORT"
    echo "watch $W: adbd listens on tcp $WATCH_PORT until the watch reboots" ;;
  *)
    echo "usage: $0 up|install <apk>|down|status|bootstrap" >&2; exit 1 ;;
esac
