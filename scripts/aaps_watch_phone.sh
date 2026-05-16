#!/usr/bin/env bash
set -euo pipefail

SERIAL="${AAPS_SERIAL:-RRCX807YMBY}"
PACKAGE="${AAPS_PACKAGE:-info.nightscout.androidaps}"
INTERVAL_SECONDS="${AAPS_WATCH_INTERVAL_SECONDS:-10}"
ADB_WIFI_PORT="${AAPS_ADB_WIFI_PORT:-5555}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COLLECTOR="$SCRIPT_DIR/aaps_collect_7d.sh"
CONNECTOR="$SCRIPT_DIR/aaps_adb_wifi_connect.sh"
STATE_DIR="${AAPS_WATCH_STATE_DIR:-$HOME/.aaps-phone-collector}"
STATE_FILE="$STATE_DIR/${SERIAL}.connected"
ADB_WIFI_IP_FILE="$STATE_DIR/${SERIAL}.adb-wifi-ip"
LOG_FILE="$STATE_DIR/watch.log"
ADB="${ADB:-/Users/alexeydedeshko/Library/Android/sdk/platform-tools/adb}"
ADB_LOCK_DIR="$STATE_DIR/adb.lock"

mkdir -p "$STATE_DIR"
touch "$LOG_FILE"
rm -f "$STATE_FILE"

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')" "$*" | tee -a "$LOG_FILE"
}

local_ipv4s() {
  local ifaces
  ifaces="$(ifconfig -l 2>/dev/null || true)"
  for iface in $ifaces; do
    case "$iface" in
      en*|bridge*)
        ipconfig getifaddr "$iface" 2>/dev/null || true
        ;;
    esac
  done | awk '/^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ && $0 !~ /^127\./ { print }' | sort -u
}

ip24_prefix() {
  printf '%s\n' "$1" | awk -F. 'NF == 4 { print $1 "." $2 "." $3 "." }'
}

ip_is_on_current_local_network() {
  local ip="$1"
  local prefix local_ip
  prefix="$(ip24_prefix "$ip")"
  [ -n "$prefix" ] || return 1
  while IFS= read -r local_ip; do
    [ "$(ip24_prefix "$local_ip")" = "$prefix" ] && return 0
  done < <(local_ipv4s)
  return 1
}

acquire_adb_lock() {
  local max_wait="${1:-5}"
  local waited=0
  while ! mkdir "$ADB_LOCK_DIR" 2>/dev/null; do
    local lock_age
    lock_age="$(( $(date +%s) - $(stat -f %m "$ADB_LOCK_DIR" 2>/dev/null || echo 0) ))"
    if [ "$lock_age" -gt 600 ]; then
      log "Removing stale ADB lock: $ADB_LOCK_DIR"
      rm -rf "$ADB_LOCK_DIR"
      continue
    fi
    if [ "$waited" -ge "$max_wait" ]; then
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done

  echo "$$" > "$ADB_LOCK_DIR/pid"
  export AAPS_ADB_LOCK_HELD=1
  return 0
}

release_adb_lock() {
  if [ "${AAPS_ADB_LOCK_HELD:-0}" = "1" ]; then
    rm -rf "$ADB_LOCK_DIR"
    unset AAPS_ADB_LOCK_HELD
  fi
}

trap release_adb_lock EXIT INT TERM

target_has_aaps_package() {
  local target="$1"
  "$ADB" -s "$target" shell pm path "$PACKAGE" 2>/dev/null \
    | tr -d '\r' \
    | grep -q '^package:'
}

target_matches_phone() {
  local target="$1"
  local actual
  actual="$("$ADB" -s "$target" shell 'getprop ro.serialno; getprop ro.boot.serialno' 2>/dev/null | tr -d '\r' | awk 'NF { print; exit }')"
  if [ "$actual" = "$SERIAL" ]; then
    return 0
  fi
  if target_has_aaps_package "$target"; then
    log "ADB target $target has AAPS package $PACKAGE; accepting it for $SERIAL."
    return 0
  fi
  log "Ignoring ADB target $target: device serial is '${actual:-unknown}', expected '$SERIAL', and AAPS package $PACKAGE was not found."
  return 1
}

wifi_device_for_phone() {
  local target
  while IFS= read -r target; do
    [ -n "$target" ] || continue
    if target_matches_phone "$target" >/dev/null; then
      printf '%s\n' "$target"
      return 0
    fi
  done < <("$ADB" devices | awk -v port=":$ADB_WIFI_PORT" '$1 ~ port "$" && $2 == "device" { print $1 }')
  return 1
}

is_connected_locked() {
  if "$ADB" devices | awk -v serial="$SERIAL" '$1 == serial && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
    return 0
  fi

  if [ -f "$ADB_WIFI_IP_FILE" ]; then
    local ip target
    ip="$(tr -d '[:space:]' < "$ADB_WIFI_IP_FILE")"
    target="$ip:$ADB_WIFI_PORT"
    if [ -n "$ip" ]; then
      if ! ip_is_on_current_local_network "$ip"; then
        log "Saved ADB Wi-Fi address $target is outside current Mac subnet; skipping direct reconnect."
        return 1
      fi
      connect_saved_wifi_locked "$target" >/dev/null 2>&1 || true
      if "$ADB" devices | awk -v target="$target" '$1 == target && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }' \
        && target_matches_phone "$target"; then
        return 0
      fi
      "$ADB" disconnect "$target" >/dev/null 2>&1 || true
      return 1
    fi
  fi

  log "Saved ADB Wi-Fi address is not connected. Searching current network."
  if AAPS_ADB_LOCK_HELD=1 "$CONNECTOR" >> "$LOG_FILE" 2>&1; then
    if wifi_device_for_phone >/dev/null; then
      return 0
    fi
    return 1
  fi

  return 1
}

connect_saved_wifi_locked() {
  local target="$1"
  local pid waited
  "$ADB" connect "$target" &
  pid=$!
  waited=0
  while kill -0 "$pid" >/dev/null 2>&1 && [ "$waited" -lt 8 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" >/dev/null 2>&1; then
    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" 2>/dev/null || true
    log "ADB connect timed out for $target."
    return 1
  fi
  wait "$pid"
}

log "Watching for AAPS phone $SERIAL. Collector: $COLLECTOR"

while true; do
  if ! acquire_adb_lock 2; then
    log "ADB is busy; skipping this watch tick."
    sleep "$INTERVAL_SECONDS"
    continue
  fi

  if is_connected_locked; then
    release_adb_lock
    if [ ! -f "$STATE_FILE" ]; then
      date '+%Y-%m-%d %H:%M:%S %z' > "$STATE_FILE"
      log "Phone connected. Starting 7-day export."
      if "$COLLECTOR" >> "$LOG_FILE" 2>&1; then
        log "Export completed."
      else
        rm -f "$STATE_FILE"
        log "Export failed. See log above."
      fi
    fi
  else
    release_adb_lock
    if [ -f "$STATE_FILE" ]; then
      rm -f "$STATE_FILE"
      log "Phone disconnected. Next connection will trigger a new export."
    fi
  fi

  sleep "$INTERVAL_SECONDS"
done
