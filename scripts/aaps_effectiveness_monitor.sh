#!/usr/bin/env bash
set -euo pipefail

SERIAL="${AAPS_SERIAL:-RRCX807YMBY}"
PACKAGE="${AAPS_PACKAGE:-info.nightscout.androidaps}"
ADB_WIFI_PORT="${AAPS_ADB_WIFI_PORT:-5555}"
INTERVAL_SECONDS="${AAPS_EFFECTIVENESS_INTERVAL_SECONDS:-60}"
EXPORT_INTERVAL_SECONDS="${AAPS_EFFECTIVENESS_EXPORT_INTERVAL_SECONDS:-1800}"
WINDOW_HOURS="${AAPS_EFFECTIVENESS_WINDOW_HOURS:-72}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
COLLECTOR="$SCRIPT_DIR/aaps_collect_7d.sh"
CONNECTOR="$SCRIPT_DIR/aaps_adb_wifi_connect.sh"
ANALYZER="$SCRIPT_DIR/aaps_analyze_effectiveness.py"
STATE_DIR="${AAPS_EFFECTIVENESS_STATE_DIR:-$HOME/.aaps-effectiveness-monitor}"
REPORT_DIR="${AAPS_EFFECTIVENESS_REPORT_DIR:-$ROOT_DIR/aaps-effectiveness-monitor}"
LOG_FILE="$STATE_DIR/monitor.log"
CONNECTED_FILE="$STATE_DIR/$SERIAL.connected"
LAST_EXPORT_FILE="$STATE_DIR/last-export-epoch"
START_FILE="$STATE_DIR/started-ms"
ADB="${ADB:-/Users/alexeydedeshko/Library/Android/sdk/platform-tools/adb}"

mkdir -p "$STATE_DIR" "$REPORT_DIR"
touch "$LOG_FILE"

log() {
  printf '%s %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')" "$*" | tee -a "$LOG_FILE"
}

now_ms() {
  printf '%s000\n' "$(date +%s)"
}

ensure_start_marker() {
  if [ ! -f "$START_FILE" ]; then
    now_ms > "$START_FILE"
  fi
}

connected_target() {
  if "$ADB" devices | awk -v serial="$SERIAL" '$1 == serial && $2 == "device" { found = 1 } END { exit found ? 0 : 1 }'; then
    printf '%s\n' "$SERIAL"
    return 0
  fi

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

latest_export_db() {
  local db
  while IFS= read -r db; do
    if sqlite3 "$db" "PRAGMA quick_check;" 2>/dev/null | head -1 | grep -qx "ok" \
      && sqlite3 "$db" "SELECT MAX(timestamp) FROM glucoseValues WHERE isValid = 1;" >/dev/null 2>&1; then
      printf '%s\n' "$db"
      return 0
    fi
    log "Пропускаю поврежденную или недокачанную базу: $db"
  done < <(
    find "$ROOT_DIR/aaps-data-exports" -path '*/raw/androidaps.db' -type f -print0 2>/dev/null \
      | xargs -0 ls -t 2>/dev/null
  )
}

run_collection_and_analysis() {
  local target db since_ms
  target="$(connected_target || true)"
  if [ -z "$target" ]; then
    log "Телефон не подключен; сбор пропущен."
    return 1
  fi

  log "Телефон подключен ($target). Собираю данные для мониторинга эффективности."
  if AAPS_SERIAL="$SERIAL" AAPS_PACKAGE="$PACKAGE" AAPS_EXPORT_DAYS=7 "$COLLECTOR" >> "$LOG_FILE" 2>&1; then
    date +%s > "$LAST_EXPORT_FILE"
    log "Сбор данных завершен."
  else
    log "Сбор данных не удался; попробую анализировать последнюю доступную выгрузку."
  fi

  db="$(latest_export_db || true)"
  if [ -z "$db" ] || [ ! -f "$db" ]; then
    log "Нет androidaps.db для анализа."
    return 1
  fi

  since_ms="$(cat "$START_FILE" 2>/dev/null || echo 0)"
  log "Строю отчет эффективности по базе: $db"
  if "$ANALYZER" --db "$db" --out-dir "$REPORT_DIR" --since-ms "$since_ms" --window-hours "$WINDOW_HOURS" >> "$LOG_FILE" 2>&1; then
    log "Отчет эффективности обновлен: $REPORT_DIR/latest_report.md"
  else
    log "Анализ эффективности не удался."
    return 1
  fi
}

ensure_start_marker
log "Монитор эффективности AIMI запущен. Отчеты: $REPORT_DIR"

while true; do
  target="$(connected_target || true)"
  if [ -n "$target" ]; then
    if [ ! -f "$CONNECTED_FILE" ]; then
      date '+%Y-%m-%d %H:%M:%S %z' > "$CONNECTED_FILE"
      log "Телефон появился: $target. Быстрый сбор после подключения."
      run_collection_and_analysis || true
    else
      last_export="$(cat "$LAST_EXPORT_FILE" 2>/dev/null || echo 0)"
      age="$(( $(date +%s) - last_export ))"
      if [ "$age" -ge "$EXPORT_INTERVAL_SECONDS" ]; then
        log "Плановый сбор во время подключения. Возраст последнего сбора: ${age}s."
        run_collection_and_analysis || true
      fi
    fi
  else
    if [ -f "$CONNECTED_FILE" ]; then
      rm -f "$CONNECTED_FILE"
      log "Телефон отключился. При следующем подключении будет быстрый сбор и новый вывод."
    fi
    AAPS_SERIAL="$SERIAL" AAPS_PACKAGE="$PACKAGE" "$CONNECTOR" >/dev/null 2>&1 || true
  fi

  sleep "$INTERVAL_SECONDS"
done
