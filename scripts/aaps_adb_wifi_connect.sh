#!/usr/bin/env bash
set -euo pipefail

SERIAL="${AAPS_SERIAL:-RRCX807YMBY}"
PORT="${AAPS_ADB_WIFI_PORT:-5555}"
ADB="${ADB:-/Users/alexeydedeshko/Library/Android/sdk/platform-tools/adb}"
STATE_DIR="${AAPS_WATCH_STATE_DIR:-$HOME/.aaps-phone-collector}"
IP_FILE="$STATE_DIR/${SERIAL}.adb-wifi-ip"
ADB_KEY_DIR="${ANDROID_ADB_SERVER_KEY_DIR:-$HOME/.android}"
ADB_KEY="$ADB_KEY_DIR/adbkey"
ADB_PUB_KEY="$ADB_KEY_DIR/adbkey.pub"
ADB_KEY_BACKUP="$STATE_DIR/adbkey"
ADB_PUB_KEY_BACKUP="$STATE_DIR/adbkey.pub"
ADB_LOCK_DIR="$STATE_DIR/adb.lock"

mkdir -p "$STATE_DIR"

log() {
  printf '%s\n' "$*"
}

acquire_adb_lock() {
  [ "${AAPS_ADB_LOCK_HELD:-0}" = "1" ] && return 0

  local waited=0
  while ! mkdir "$ADB_LOCK_DIR" 2>/dev/null; do
    if [ -f "$ADB_LOCK_DIR/pid" ]; then
      local lock_age
      lock_age="$(( $(date +%s) - $(stat -f %m "$ADB_LOCK_DIR" 2>/dev/null || echo 0) ))"
      if [ "$lock_age" -gt 600 ]; then
        log "Removing stale ADB lock: $ADB_LOCK_DIR"
        rm -rf "$ADB_LOCK_DIR"
        continue
      fi
    fi
    if [ "$waited" -ge 60 ]; then
      log "Timed out waiting for ADB lock: $ADB_LOCK_DIR"
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done

  echo "$$" > "$ADB_LOCK_DIR/pid"
  export AAPS_ADB_LOCK_HELD=1
  trap 'rm -rf "$ADB_LOCK_DIR"' EXIT INT TERM
}

preserve_adb_key() {
  mkdir -p "$ADB_KEY_DIR" "$STATE_DIR"

  if [ -f "$ADB_KEY" ] && [ -f "$ADB_PUB_KEY" ]; then
    if [ ! -f "$ADB_KEY_BACKUP" ] || ! cmp -s "$ADB_KEY" "$ADB_KEY_BACKUP"; then
      cp "$ADB_KEY" "$ADB_KEY_BACKUP"
      chmod 600 "$ADB_KEY_BACKUP"
    fi
    if [ ! -f "$ADB_PUB_KEY_BACKUP" ] || ! cmp -s "$ADB_PUB_KEY" "$ADB_PUB_KEY_BACKUP"; then
      cp "$ADB_PUB_KEY" "$ADB_PUB_KEY_BACKUP"
      chmod 644 "$ADB_PUB_KEY_BACKUP"
    fi
    log "ADB key is present and backed up."
    return 0
  fi

  if [ -f "$ADB_KEY_BACKUP" ] && [ -f "$ADB_PUB_KEY_BACKUP" ]; then
    cp "$ADB_KEY_BACKUP" "$ADB_KEY"
    cp "$ADB_PUB_KEY_BACKUP" "$ADB_PUB_KEY"
    chmod 600 "$ADB_KEY"
    chmod 644 "$ADB_PUB_KEY"
    log "ADB key was restored from backup."
    return 0
  fi

  log "ADB key backup is not available yet. Android may ask for one-time authorization."
}

device_state() {
  "$ADB" devices | awk -v serial="$1" '$1 == serial { print $2; found = 1 } END { if (!found) print "" }'
}

first_usb_device() {
  "$ADB" devices | awk -v serial="$SERIAL" '$1 == serial && $2 == "device" { print $1; exit }'
}

phone_ip_from_usb() {
  "$ADB" -s "$SERIAL" shell ip -f inet addr 2>/dev/null \
    | awk '
        /^[0-9]+:/ { iface = $2; sub(/:/, "", iface) }
        /inet / && iface != "lo" { sub(/\/.*/, "", $2); print $2; exit }
      ' \
    | tr -d '\r'
}

connect_ip() {
  local ip="$1"
  local target="$ip:$PORT"
  local pid status waited
  "$ADB" disconnect "$target" >/dev/null 2>&1 || true
  "$ADB" connect "$target" >/tmp/aaps_adb_wifi_connect.out 2>&1 &
  pid=$!
  waited=0
  while kill -0 "$pid" >/dev/null 2>&1 && [ "$waited" -lt 8 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" >/dev/null 2>&1; then
    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" 2>/dev/null || true
    cat /tmp/aaps_adb_wifi_connect.out
    log "ADB connect timed out or failed for $target."
    return 1
  fi
  wait "$pid"
  status=$?
  if [ "$status" -ne 0 ]; then
    cat /tmp/aaps_adb_wifi_connect.out
    log "ADB connect timed out or failed for $target."
    return 1
  fi
  cat /tmp/aaps_adb_wifi_connect.out
  sleep 1
  if "$ADB" devices | awk -v target="$target" '$1 == target && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
    echo "$ip" > "$IP_FILE"
    log "ADB Wi-Fi connected: $ip:$PORT"
    "$ADB" devices -l
    return 0
  fi
  log "ADB Wi-Fi target $target is not online as device."
  "$ADB" devices -l
  return 1
}

known_wifi_device_ip() {
  "$ADB" devices | awk -v port=":$PORT" '$1 ~ port "$" && $2 == "device" { sub(port "$", "", $1); print $1; exit }'
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

diagnose_wifi_reachability() {
  local ip="$1"
  log "Wi-Fi reachability check for $ip:$PORT..."
  if ping -c 1 -W 1000 "$ip" >/dev/null 2>&1; then
    log "Phone answers ping from Mac."
  else
    log "Phone does not answer ping from Mac. Wi-Fi client isolation, VPN routing, or phone Wi-Fi firewall may block peer-to-peer traffic."
  fi

  if nc -z -G 2 "$ip" "$PORT" >/dev/null 2>&1; then
    log "ADB port $PORT is reachable from Mac."
  else
    log "ADB port $PORT is not reachable from Mac."
  fi
}

known_unauthorized_device() {
  "$ADB" devices | awk '$2 == "unauthorized" { print $1; exit }'
}

connect_mdns_services() {
  local services target
  services="$("$ADB" mdns services 2>/dev/null || true)"
  printf '%s\n' "$services" | sed '/^$/d'
  target="$(printf '%s\n' "$services" | awk '/_adb-tls-connect\._tcp|_adb\._tcp/ { print $NF; exit }')"
  if [ -n "$target" ]; then
    log "Trying Android Wireless Debugging mDNS target: $target..."
    "$ADB" connect "$target" || true
    sleep 1
    "$ADB" devices -l
    if "$ADB" devices | awk -v target="$target" '$1 == target && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
      log "ADB Wi-Fi connected via Android Wireless Debugging: $target"
      return 0
    fi
  fi
  return 1
}

adb_port_candidates() {
  {
    arp -an 2>/dev/null \
      | awk '{ gsub(/[()]/, "", $2); print $2 }' \
      | awk '/^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$/ && $0 !~ /^127\./'

    local_ipv4s \
    | awk -F. '{ for (i = 1; i <= 254; i++) if (i != $4) print $1 "." $2 "." $3 "." i }'
  } \
    | sort -u \
    | PORT="$PORT" xargs -n 1 -P 96 sh -c '
      ip="$1"
      nc -z -w 1 -G 1 "$ip" "$PORT" >/dev/null 2>&1 && printf "%s\n" "$ip"
    ' sh \
    | sort -u
}

find_phone_on_current_network() {
  local ip
  log "Scanning current local network for ADB Wi-Fi port $PORT..."
  while IFS= read -r ip; do
    [ -n "$ip" ] || continue
    log "Found open ADB port candidate: $ip:$PORT"
    if connect_ip "$ip"; then
      return 0
    fi
  done < <(adb_port_candidates)
  return 1
}

enable_wifi_from_usb() {
  log "USB device is available. Enabling ADB over TCP/IP on port $PORT..."
  "$ADB" -s "$SERIAL" tcpip "$PORT"
  sleep 2

  IP="$(phone_ip_from_usb)"
  if [ -z "$IP" ]; then
    log "Could not read phone Wi-Fi IP. Make sure the phone is connected to Wi-Fi."
    return 2
  fi

  log "Phone Wi-Fi IP: $IP"
  echo "$IP" > "$IP_FILE"
  if connect_ip "$IP"; then
    return 0
  fi
  diagnose_wifi_reachability "$IP"
  log "USB is still available, so data collection can continue over USB. For Wi-Fi ADB, disable client/AP isolation or use a direct trusted network."
  return 1
}

log "AAPS ADB Wi-Fi connector for $SERIAL"

IP="$(known_wifi_device_ip)"
if [ -n "$IP" ]; then
  log "ADB Wi-Fi is already connected: $IP:$PORT"
  echo "$IP" > "$IP_FILE"
  "$ADB" devices -l
  exit 0
fi

acquire_adb_lock
preserve_adb_key
log "Mac local IPv4 addresses: $(local_ipv4s | tr '\n' ' ')"

IP="$(known_wifi_device_ip)"
if [ -n "$IP" ]; then
  log "ADB Wi-Fi is already connected: $IP:$PORT"
  echo "$IP" > "$IP_FILE"
  "$ADB" devices -l
  exit 0
fi

if [ "$(device_state "$SERIAL")" = "device" ]; then
  enable_wifi_from_usb
  exit $?
fi

if [ -f "$IP_FILE" ]; then
  IP="$(tr -d '[:space:]' < "$IP_FILE")"
  if [ -n "$IP" ]; then
    if ip_is_on_current_local_network "$IP"; then
      log "Trying saved Wi-Fi address: $IP:$PORT..."
      if connect_ip "$IP"; then
        exit 0
      fi
    else
      log "Saved Wi-Fi address $IP:$PORT is not on the current local network. Skipping it."
    fi
  fi
fi

if connect_mdns_services; then
  exit 0
fi

if find_phone_on_current_network; then
  exit 0
fi

UNAUTHORIZED="$(known_unauthorized_device)"
if [ -n "$UNAUTHORIZED" ]; then
  log "ADB sees $UNAUTHORIZED but it is unauthorized."
  log "Unlock the phone, accept the RSA debugging prompt, and enable Always allow from this computer."
  exit 3
fi

log "No USB device and no working saved Wi-Fi connection."
log "Make sure the phone and this Mac are on the same Wi-Fi network and the phone is unlocked."
log "USB is only needed if ADB over TCP/IP has never been enabled on this phone."
exit 1
