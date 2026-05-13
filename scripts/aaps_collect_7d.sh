#!/usr/bin/env bash
set -euo pipefail

SERIAL="${AAPS_SERIAL:-RRCX807YMBY}"
PACKAGE="${AAPS_PACKAGE:-info.nightscout.androidaps}"
DAYS="${AAPS_EXPORT_DAYS:-7}"
ADB_WIFI_PORT="${AAPS_ADB_WIFI_PORT:-5555}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
CONNECTOR="$SCRIPT_DIR/aaps_adb_wifi_connect.sh"
OUT_ROOT="${AAPS_EXPORT_ROOT:-$ROOT_DIR/aaps-data-exports}"
ADB="${ADB:-/Users/alexeydedeshko/Library/Android/sdk/platform-tools/adb}"
SQLITE="${SQLITE:-sqlite3}"
STATE_DIR="${AAPS_WATCH_STATE_DIR:-$HOME/.aaps-phone-collector}"
ADB_WIFI_IP_FILE="$STATE_DIR/${SERIAL}.adb-wifi-ip"
ADB_LOCK_DIR="$STATE_DIR/adb.lock"
ADB_TARGET=""

need_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required tool: $1" >&2
    exit 1
  fi
}

acquire_adb_lock() {
  [ "${AAPS_ADB_LOCK_HELD:-0}" = "1" ] && return 0

  local waited=0
  mkdir -p "$STATE_DIR"
  while ! mkdir "$ADB_LOCK_DIR" 2>/dev/null; do
    local lock_age
    lock_age="$(( $(date +%s) - $(stat -f %m "$ADB_LOCK_DIR" 2>/dev/null || echo 0) ))"
    if [ "$lock_age" -gt 600 ]; then
      echo "Removing stale ADB lock: $ADB_LOCK_DIR" >&2
      rm -rf "$ADB_LOCK_DIR"
      continue
    fi
    if [ "$waited" -ge 120 ]; then
      echo "Timed out waiting for ADB lock: $ADB_LOCK_DIR" >&2
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done

  echo "$$" > "$ADB_LOCK_DIR/pid"
  export AAPS_ADB_LOCK_HELD=1
  trap 'rm -rf "$ADB_LOCK_DIR"' EXIT INT TERM
}

run_adb() {
  "$ADB" -s "$ADB_TARGET" "$@"
}

run_adb_retry() {
  local attempt=1
  while true; do
    if run_adb "$@"; then
      return 0
    fi
    if [ "$attempt" -ge 3 ]; then
      return 1
    fi
    echo "ADB command failed; retrying ($attempt/3): $*" >&2
    "$ADB" start-server >/dev/null 2>&1 || true
    sleep 2
    attempt=$((attempt + 1))
  done
}

adb_device_state() {
  "$ADB" devices | awk -v serial="$1" '$1 == serial { print $2; found = 1 } END { if (!found) print "" }'
}

resolve_adb_target() {
  if [ "$(adb_device_state "$SERIAL")" = "device" ]; then
    echo "$SERIAL"
    return 0
  fi

  if [ -f "$ADB_WIFI_IP_FILE" ]; then
    local ip target
    ip="$(tr -d '[:space:]' < "$ADB_WIFI_IP_FILE")"
    target="$ip:$ADB_WIFI_PORT"
    if [ -n "$ip" ]; then
      "$ADB" connect "$target" >/dev/null 2>&1 || true
      if [ "$(adb_device_state "$target")" = "device" ]; then
        echo "$target"
        return 0
      fi
    fi
  fi

  "$CONNECTOR" >/dev/null 2>&1 || true
  local wifi_target
  wifi_target="$("$ADB" devices | awk -v port=":$ADB_WIFI_PORT" '$1 ~ port "$" && $2 == "device" { print $1; exit }')"
  if [ -n "$wifi_target" ]; then
    echo "$wifi_target"
    return 0
  fi

  return 1
}

csv_query() {
  local db="$1"
  local sql="$2"
  local output="$3"
  "$SQLITE" -header -csv "$db" "$sql" > "$output"
}

sql_value() {
  local db="$1"
  local sql="$2"
  "$SQLITE" -noheader "$db" "$sql"
}

quote_csv_cell() {
  local value="${1//\"/\"\"}"
  printf '"%s"' "$value"
}

need_tool "$ADB"
need_tool "$SQLITE"
acquire_adb_lock

if ! ADB_TARGET="$(resolve_adb_target)"; then
  echo "Device $SERIAL is not connected or not authorized over USB/Wi-Fi." >&2
  exit 2
fi

TS="$(date '+%Y%m%d-%H%M%S')"
OUT_DIR="$OUT_ROOT/$TS"
RAW_DIR="$OUT_DIR/raw"
CSV_DIR="$OUT_DIR/csv"
REPORT_DIR="$OUT_DIR/reports"
LOG_DIR="$OUT_DIR/logs"
mkdir -p "$RAW_DIR" "$CSV_DIR" "$REPORT_DIR" "$LOG_DIR"

DB="$RAW_DIR/androidaps.db"
WAL="$RAW_DIR/androidaps.db-wal"
SHM="$RAW_DIR/androidaps.db-shm"
ARCHIVE="$RAW_DIR/androidaps-db.tgz"

echo "Collecting AAPS data from $ADB_TARGET for last $DAYS days..."

run_adb_retry shell run-as "$PACKAGE" cp databases/androidaps.db /sdcard/androidaps.db
run_adb_retry shell run-as "$PACKAGE" cp databases/androidaps.db-wal /sdcard/androidaps.db-wal >/dev/null 2>&1 || true
run_adb_retry shell run-as "$PACKAGE" cp databases/androidaps.db-shm /sdcard/androidaps.db-shm >/dev/null 2>&1 || true

if [[ "$ADB_TARGET" == *:* ]]; then
  echo "Wi-Fi ADB detected; compressing database on phone before pull..."
  run_adb_retry shell "cd /sdcard && tar -czf androidaps-db.tgz androidaps.db androidaps.db-wal androidaps.db-shm 2>/dev/null || tar -czf androidaps-db.tgz androidaps.db"
  run_adb_retry pull /sdcard/androidaps-db.tgz "$ARCHIVE" >/dev/null
  tar -xzf "$ARCHIVE" -C "$RAW_DIR"
else
  run_adb_retry pull /sdcard/androidaps.db "$DB" >/dev/null
  run_adb_retry pull /sdcard/androidaps.db-wal "$WAL" >/dev/null 2>&1 || true
  run_adb_retry pull /sdcard/androidaps.db-shm "$SHM" >/dev/null 2>&1 || true
fi

if ! "$SQLITE" "$DB" "PRAGMA quick_check;" 2>/dev/null | head -1 | grep -qx "ok"; then
  echo "Pulled androidaps.db is not valid; export will be ignored." >&2
  exit 3
fi

run_adb logcat -d > "$LOG_DIR/logcat_snapshot.txt" 2>/dev/null || true
if command -v rg >/dev/null 2>&1; then
  rg "AIMI|BolusWizard|APS|Result: RT|IOB SOURCE CHECK|IOB FOR SMB|PAI" "$LOG_DIR/logcat_snapshot.txt" > "$LOG_DIR/logcat_aimi_filtered.txt" 2>/dev/null || true
else
  grep -E "AIMI|BolusWizard|APS|Result: RT|IOB SOURCE CHECK|IOB FOR SMB|PAI" "$LOG_DIR/logcat_snapshot.txt" > "$LOG_DIR/logcat_aimi_filtered.txt" 2>/dev/null || true
fi

CUTOFF_MS="$(( ($(date +%s) - DAYS * 24 * 60 * 60) * 1000 ))"
CUTOFF_LOCAL="$(date -r "$((CUTOFF_MS / 1000))" '+%Y-%m-%d %H:%M:%S %z')"

"$SQLITE" "$DB" ".schema" > "$REPORT_DIR/schema.sql"
"$SQLITE" -line "$DB" "PRAGMA database_list;" > "$REPORT_DIR/database_info.txt"

{
  echo "created_at=$(date '+%Y-%m-%d %H:%M:%S %z')"
  echo "device_serial=$SERIAL"
  echo "adb_target=$ADB_TARGET"
  echo "package=$PACKAGE"
  echo "days=$DAYS"
  echo "cutoff_ms=$CUTOFF_MS"
  echo "cutoff_local=$CUTOFF_LOCAL"
  echo "repo=$ROOT_DIR"
  echo "branch=$(git -C "$ROOT_DIR" branch --show-current 2>/dev/null || true)"
  echo "head=$(git -C "$ROOT_DIR" rev-parse --short HEAD 2>/dev/null || true)"
  run_adb shell getprop ro.product.model 2>/dev/null | sed 's/^/phone_model=/'
  if command -v rg >/dev/null 2>&1; then
    run_adb shell dumpsys package "$PACKAGE" 2>/dev/null | rg "versionName|versionCode|lastUpdateTime" || true
  else
    run_adb shell dumpsys package "$PACKAGE" 2>/dev/null | grep -E "versionName|versionCode|lastUpdateTime" || true
  fi
} > "$REPORT_DIR/metadata.txt"

TABLES="$(sql_value "$DB" "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name;")"
printf "table,total_rows,last_7d_rows,min_time,max_time\n" > "$REPORT_DIR/row_counts.csv"

while IFS= read -r table; do
  [ -n "$table" ] || continue

  total_rows="$(sql_value "$DB" "SELECT COUNT(*) FROM \"$table\";")"
  has_timestamp="$(sql_value "$DB" "SELECT COUNT(*) FROM pragma_table_info('$table') WHERE name='timestamp';")"

  if [ "$has_timestamp" = "1" ]; then
    last_rows="$(sql_value "$DB" "SELECT COUNT(*) FROM \"$table\" WHERE timestamp >= $CUTOFF_MS;")"
    min_time="$(sql_value "$DB" "SELECT COALESCE(datetime(MIN(timestamp)/1000,'unixepoch','localtime'),'') FROM \"$table\";")"
    max_time="$(sql_value "$DB" "SELECT COALESCE(datetime(MAX(timestamp)/1000,'unixepoch','localtime'),'') FROM \"$table\";")"
    csv_query "$DB" "SELECT datetime(timestamp/1000,'unixepoch','localtime') AS localTime, * FROM \"$table\" WHERE timestamp >= $CUTOFF_MS ORDER BY timestamp;" "$CSV_DIR/${table}_last_${DAYS}d.csv"
  else
    last_rows="$total_rows"
    min_time=""
    max_time=""
    csv_query "$DB" "SELECT * FROM \"$table\";" "$CSV_DIR/${table}.csv"
  fi

  {
    quote_csv_cell "$table"; printf ',%s,%s,' "$total_rows" "$last_rows"
    quote_csv_cell "$min_time"; printf ','
    quote_csv_cell "$max_time"; printf '\n'
  } >> "$REPORT_DIR/row_counts.csv"
done <<< "$TABLES"

csv_query "$DB" "
SELECT
  datetime(timestamp/1000,'unixepoch','localtime') AS time,
  timestamp,
  algorithm,
  json_extract(glucoseStatusJson,'$.glucose') AS bg,
  json_extract(glucoseStatusJson,'$.delta') AS delta,
  json_extract(glucoseStatusJson,'$.shortAvgDelta') AS shortDelta,
  json_extract(glucoseStatusJson,'$.longAvgDelta') AS longDelta,
  json_extract(glucoseStatusJson,'$.bgAcceleration') AS accel,
  json_extract(profileJson,'$.out_units') AS units,
  json_extract(profileJson,'$.dia') AS DIA_hr,
  json_extract(profileJson,'$.sens') AS ISF_profile,
  json_extract(profileJson,'$.carb_ratio') AS CR,
  json_extract(profileJson,'$.max_iob') AS maxIOB,
  json_extract(profileJson,'$.enableUAM') AS enableUAM,
  json_extract(profileJson,'$.TDD') AS TDD_profile,
  json_extract(autosensDataJson,'$.ratio') AS autosens_ratio,
  json_extract(mealDataJson,'$.COB') AS COB,
  json_extract(mealDataJson,'$.slopeFromMinDeviation') AS mealSlope,
  json_extract(resultJson,'$.eventualBG') AS eventualBG,
  json_extract(resultJson,'$.targetBG') AS targetBG,
  json_extract(resultJson,'$.insulinReq') AS insulinReq,
  json_extract(resultJson,'$.carbsReq') AS carbsReq,
  json_extract(resultJson,'$.rate') AS tempBasalRate,
  json_extract(resultJson,'$.duration') AS tempBasalDuration,
  json_extract(resultJson,'$.reason') AS reason,
  json_extract(resultJson,'$.consoleLog') AS consoleLog
FROM apsResults
WHERE timestamp >= $CUTOFF_MS
ORDER BY timestamp;
" "$CSV_DIR/decision_snapshots_last_${DAYS}d.csv"

csv_query "$DB" "
SELECT
  datetime(ar.timestamp/1000,'unixepoch','localtime') AS time,
  ar.timestamp,
  je.value AS line
FROM apsResults ar,
     json_each(json_extract(ar.resultJson,'$.consoleLog')) je
WHERE ar.timestamp >= $CUTOFF_MS
  AND (
    je.value LIKE '%IOB SOURCE CHECK%'
    OR je.value LIKE '%IOB FOR SMB%'
    OR je.value LIKE '%AIMI%'
    OR je.value LIKE '%PAI%'
  )
ORDER BY ar.timestamp;
" "$CSV_DIR/aimi_console_lines_last_${DAYS}d.csv"

csv_query "$DB" "
WITH gv AS (
  SELECT timestamp, value
  FROM glucoseValues
  WHERE isValid = 1 AND timestamp >= $CUTOFF_MS
),
days AS (
  SELECT date(timestamp/1000,'unixepoch','localtime') AS day, value
  FROM gv
)
SELECT
  day,
  COUNT(*) AS points,
  ROUND(AVG(value), 1) AS avg_bg,
  ROUND(100.0 * SUM(value BETWEEN 70 AND 180) / COUNT(*), 1) AS tir_70_180_pct,
  ROUND(100.0 * SUM(value < 70) / COUNT(*), 1) AS below_70_pct,
  ROUND(100.0 * SUM(value < 54) / COUNT(*), 1) AS below_54_pct,
  ROUND(100.0 * SUM(value > 180) / COUNT(*), 1) AS above_180_pct,
  ROUND(100.0 * SUM(value > 250) / COUNT(*), 1) AS above_250_pct
FROM days
GROUP BY day
ORDER BY day;
" "$REPORT_DIR/glucose_daily_metrics.csv"

csv_query "$DB" "
SELECT 'bolus' AS kind, datetime(timestamp/1000,'unixepoch','localtime') AS time, timestamp, amount, type, notes
FROM boluses WHERE timestamp >= $CUTOFF_MS AND isValid = 1
UNION ALL
SELECT 'carbs' AS kind, datetime(timestamp/1000,'unixepoch','localtime') AS time, timestamp, amount, CAST(duration AS TEXT), notes
FROM carbs WHERE timestamp >= $CUTOFF_MS AND isValid = 1
UNION ALL
SELECT 'tempTarget' AS kind, datetime(timestamp/1000,'unixepoch','localtime') AS time, timestamp, lowTarget, highTarget || ' / ' || duration, reason
FROM temporaryTargets WHERE timestamp >= $CUTOFF_MS AND isValid = 1
UNION ALL
SELECT 'therapyEvent' AS kind, datetime(timestamp/1000,'unixepoch','localtime') AS time, timestamp, glucose, type, note
FROM therapyEvents WHERE timestamp >= $CUTOFF_MS AND isValid = 1
ORDER BY timestamp;
" "$CSV_DIR/events_timeline_last_${DAYS}d.csv"

{
  echo "AAPS export: last $DAYS days"
  echo "Output: $OUT_DIR"
  echo "Cutoff: $CUTOFF_LOCAL"
  echo
  echo "Key files:"
  echo "- raw/androidaps.db (+ wal/shm if present): full copied database"
  echo "- csv/decision_snapshots_last_${DAYS}d.csv: APS/AIMI decision inputs and outputs"
  echo "- csv/*_last_${DAYS}d.csv: timestamped tables filtered to last $DAYS days"
  echo "- csv/events_timeline_last_${DAYS}d.csv: boluses, carbs, temp targets, therapy events"
  echo "- reports/glucose_daily_metrics.csv: daily TIR/high/low overview"
  echo "- logs/logcat_aimi_filtered.txt: current logcat buffer filtered by AIMI/APS markers"
  echo
  echo "Row counts:"
  sed -n '1,80p' "$REPORT_DIR/row_counts.csv"
} > "$REPORT_DIR/README.txt"

ln -sfn "$OUT_DIR" "$OUT_ROOT/latest"
echo "Done: $OUT_DIR"
