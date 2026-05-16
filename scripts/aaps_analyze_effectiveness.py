#!/usr/bin/env python3
"""Build a Russian monitoring report for AAPS/AIMI forecast effectiveness."""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import sqlite3
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from statistics import median
from typing import Any


FIVE_MIN_MS = 5 * 60 * 1000


def local_time(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000).strftime("%Y-%m-%d %H:%M:%S")


def read_json(value: str | None) -> dict[str, Any]:
    if not value:
        return {}
    try:
        parsed = json.loads(value)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        return {}


def number(value: Any, default: float = 0.0) -> float:
    if value is None:
        return default
    try:
        if isinstance(value, str):
            value = value.replace(",", ".")
        result = float(value)
        return result if math.isfinite(result) else default
    except (TypeError, ValueError):
        return default


@dataclass
class GlucosePoint:
    timestamp: int
    value: float


def first_bg_at_or_after(points: list[GlucosePoint], timestamp: int) -> float | None:
    lo = 0
    hi = len(points)
    while lo < hi:
        mid = (lo + hi) // 2
        if points[mid].timestamp < timestamp:
            lo = mid + 1
        else:
            hi = mid
    if lo >= len(points):
        return None
    return points[lo].value


def min_bg_between(points: list[GlucosePoint], start: int, end: int) -> float | None:
    values = [p.value for p in points if start <= p.timestamp <= end]
    return min(values) if values else None


def has_newer_bg_between(points: list[GlucosePoint], start: int, end: int) -> bool:
    return any(start < p.timestamp <= end for p in points)


def prediction_at(result: dict[str, Any], name: str, minutes: int) -> float | None:
    pred_bgs = result.get("predBGs")
    if not isinstance(pred_bgs, dict):
        return None
    series = pred_bgs.get(name)
    if not isinstance(series, list):
        return None
    index = minutes // 5
    if index >= len(series):
        return None
    value = number(series[index], default=math.nan)
    return value if math.isfinite(value) else None


def had_low_recent(points: list[GlucosePoint], timestamp: int) -> bool:
    recent = [p for p in points if timestamp - 90 * 60 * 1000 <= p.timestamp < timestamp]
    if not recent:
        return False
    values = [p.value for p in recent]
    if min(values) <= 75:
        return True
    if len(recent) >= 2:
        first = recent[0].value
        last = recent[-1].value
        return first - last >= 18 and last <= 95
    return False


def recent_smb_units(conn: sqlite3.Connection, timestamp: int, minutes: int = 60) -> float:
    start = timestamp - minutes * 60 * 1000
    row = conn.execute(
        """
        SELECT COALESCE(SUM(amount), 0)
        FROM boluses
        WHERE isValid = 1
          AND type = 'SMB'
          AND timestamp >= ?
          AND timestamp < ?
        """,
        (start, timestamp),
    ).fetchone()
    return number(row[0]) if row else 0.0


def recent_carbs(conn: sqlite3.Connection, timestamp: int, minutes_before: int = 40, minutes_after: int = 10) -> float:
    start = timestamp - minutes_before * 60 * 1000
    end = timestamp + minutes_after * 60 * 1000
    row = conn.execute(
        """
        SELECT COALESCE(SUM(amount), 0)
        FROM carbs
        WHERE isValid = 1
          AND timestamp BETWEEN ? AND ?
        """,
        (start, end),
    ).fetchone()
    return number(row[0]) if row else 0.0


def classify_event(
    *,
    bg: float,
    delta: float,
    short_delta: float,
    long_delta: float,
    meal_cob: float,
    meal_slope: float,
    low_recent: bool,
    carbs_recent: float,
    fresh_smb: float,
) -> str:
    if low_recent and delta >= 2.0 and short_delta > 0.5:
        return "быстрый спасательный отскок"
    if carbs_recent > 0 and meal_cob > 0 and delta >= 1.0:
        return "введенные углеводы"
    if delta >= 2.0 and short_delta > 0.5 and long_delta > 0.5 and meal_slope >= 0.8 and fresh_smb < 0.3:
        return "обычная невведенная еда"
    if bg <= 90 or (bg < 110 and delta < 0):
        return "риск снижения"
    return "обычный цикл"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", required=True, help="Path to androidaps.db")
    parser.add_argument("--out-dir", required=True, help="Directory for monitoring reports")
    parser.add_argument("--since-ms", type=int, default=0, help="Analyze APS decisions after this timestamp")
    parser.add_argument("--window-hours", type=float, default=48.0, help="Fallback lookback window")
    args = parser.parse_args()

    db = Path(args.db)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    if not db.exists():
        raise SystemExit(f"DB not found: {db}")

    conn = sqlite3.connect(str(db))
    conn.row_factory = sqlite3.Row

    newest_row = conn.execute("SELECT MAX(timestamp) FROM glucoseValues WHERE isValid = 1").fetchone()
    newest_ts = int(newest_row[0] or 0)
    if newest_ts <= 0:
        raise SystemExit("No glucose data found")

    waiting_for_new_data = args.since_ms > newest_ts
    if waiting_for_new_data:
        cutoff = int(newest_ts - args.window_hours * 60 * 60 * 1000)
    else:
        cutoff = args.since_ms if args.since_ms > 0 else int(newest_ts - args.window_hours * 60 * 60 * 1000)

    glucose_points = [
        GlucosePoint(int(row["timestamp"]), number(row["value"]))
        for row in conn.execute(
            """
            SELECT timestamp, value
            FROM glucoseValues
            WHERE isValid = 1
              AND timestamp >= ?
            ORDER BY timestamp
            """,
            (cutoff - 4 * 60 * 60 * 1000,),
        )
    ]

    decisions: list[dict[str, Any]] = []
    for row in conn.execute(
        """
        SELECT timestamp, dateCreated, glucoseStatusJson, mealDataJson, profileJson, currentTempJson, resultJson
        FROM apsResults
        WHERE isValid = 1
          AND timestamp >= ?
        ORDER BY timestamp
        """,
        (cutoff,),
    ):
        ts = int(row["timestamp"])
        date_created = int(row["dateCreated"] or ts)
        gs = read_json(row["glucoseStatusJson"])
        meal = read_json(row["mealDataJson"])
        profile_json = read_json(row["profileJson"])
        current_temp = read_json(row["currentTempJson"])
        result = read_json(row["resultJson"])
        reason = str(result.get("reason") or "")

        bg = number(gs.get("glucose") or result.get("bg"))
        bg_snapshot_ts = int(number(gs.get("date"), default=ts))
        delta = number(gs.get("delta"))
        short_delta = number(gs.get("shortAvgDelta"))
        long_delta = number(gs.get("longAvgDelta"))
        meal_cob = number(meal.get("mealCOB") or meal.get("COB") or meal.get("cob"))
        meal_slope = number(meal.get("slopeFromMinDeviation"))
        target = number(result.get("targetBG"))
        forecast = number(result.get("predictedBG") or result.get("eventualBG"))
        eventual = number(result.get("eventualBG") or forecast)
        min_guard = number(result.get("minGuardBG") or min(bg, forecast, eventual))
        units = number(result.get("units"))
        rate = number(result.get("rate"))
        duration = number(result.get("duration"))
        profile_current_basal = number(profile_json.get("current_basal"))
        current_temp_rate = number(current_temp.get("rate"))
        fresh_smb = recent_smb_units(conn, ts)
        carbs_recent = recent_carbs(conn, ts)
        low_recent = had_low_recent(glucose_points, ts)
        event_type = classify_event(
            bg=bg,
            delta=delta,
            short_delta=short_delta,
            long_delta=long_delta,
            meal_cob=meal_cob,
            meal_slope=meal_slope,
            low_recent=low_recent,
            carbs_recent=carbs_recent,
            fresh_smb=fresh_smb,
        )

        actual_15 = first_bg_at_or_after(glucose_points, ts + 15 * 60 * 1000)
        actual_30 = first_bg_at_or_after(glucose_points, ts + 30 * 60 * 1000)
        actual_60 = first_bg_at_or_after(glucose_points, ts + 60 * 60 * 1000)
        actual_120 = first_bg_at_or_after(glucose_points, ts + 120 * 60 * 1000)
        min_180 = min_bg_between(glucose_points, ts, ts + 180 * 60 * 1000)
        result_delay_sec = max(0.0, (date_created - ts) / 1000.0)
        bg_snapshot_age_sec = max(0.0, (ts - bg_snapshot_ts) / 1000.0)
        newer_bg_before_result = has_newer_bg_between(glucose_points, bg_snapshot_ts + 30 * 1000, date_created)
        stale_or_delayed_result = newer_bg_before_result
        aimi_final_15 = prediction_at(result, "AIMI_FINAL", 15)
        aimi_final_30 = prediction_at(result, "AIMI_FINAL", 30)
        aimi_final_60 = prediction_at(result, "AIMI_FINAL", 60)
        soft_momentum_15 = prediction_at(result, "AIMI_MOMENTUM_SOFT", 15)
        soft_momentum_30 = prediction_at(result, "AIMI_MOMENTUM_SOFT", 30)
        soft_momentum_60 = prediction_at(result, "AIMI_MOMENTUM_SOFT", 60)
        aimi_final_error_15 = None if actual_15 is None or aimi_final_15 is None else aimi_final_15 - actual_15
        aimi_final_error_30 = None if actual_30 is None or aimi_final_30 is None else aimi_final_30 - actual_30
        forecast_error_60 = None if actual_60 is None else forecast - actual_60
        aimi_final_error_60 = None if actual_60 is None or aimi_final_60 is None else aimi_final_60 - actual_60
        soft_momentum_error_15 = None if actual_15 is None or soft_momentum_15 is None else soft_momentum_15 - actual_15
        soft_momentum_error_30 = None if actual_30 is None or soft_momentum_30 is None else soft_momentum_30 - actual_30
        soft_momentum_error_60 = None if actual_60 is None or soft_momentum_60 is None else soft_momentum_60 - actual_60
        soft_momentum_winner_60 = (
            actual_60 is not None
            and aimi_final_error_60 is not None
            and soft_momentum_error_60 is not None
            and abs(soft_momentum_error_60) < abs(aimi_final_error_60)
        )
        below_target_after = actual_60 is not None and target > 0 and actual_60 < target - 5
        hypo_after = min_180 is not None and min_180 < 70
        smb_or_high_tbr = units > 0.0 or (rate > 0 and duration > 0 and target > 0 and rate > 1.05)
        expected_basal_cap = profile_current_basal * 0.50 if profile_current_basal > 0 else 0.0
        late_basal_reduction = (
            profile_current_basal > 0
            and duration > 0
            and rate > expected_basal_cap
            and target > 0
            and bg < target - 15
            and (min_guard < target - 10 or forecast < target - 10)
            and (delta <= 0.5 or short_delta <= 0.5)
        )
        hyper_kicker = "Global Hyper Kicker (Active)" in reason
        hyper_kicker_blocked = "Global Hyper Kicker заблокирован" in reason
        low_forecast_with_hyper_kicker = hyper_kicker and target > 0 and forecast < target

        decisions.append(
            {
                "time": local_time(ts),
                "timestamp": ts,
                "type": event_type,
                "bg": round(bg, 1),
                "delta": round(delta, 2),
                "short_delta": round(short_delta, 2),
                "long_delta": round(long_delta, 2),
                "meal_cob": round(meal_cob, 2),
                "carbs_recent": round(carbs_recent, 1),
                "fresh_smb_60m": round(fresh_smb, 2),
                "target": round(target, 1),
                "forecast": round(forecast, 1),
                "eventual": round(eventual, 1),
                "min_guard": round(min_guard, 1),
                "smb_units": round(units, 3),
                "rate": round(rate, 3),
                "duration": round(duration, 1),
                "profile_current_basal": round(profile_current_basal, 3),
                "current_temp_rate": round(current_temp_rate, 3),
                "actual_15": actual_15,
                "actual_30": actual_30,
                "actual_60": actual_60,
                "actual_120": actual_120,
                "min_180": min_180,
                "result_delay_sec": round(result_delay_sec, 1),
                "bg_snapshot_age_sec": round(bg_snapshot_age_sec, 1),
                "newer_bg_before_result": newer_bg_before_result,
                "stale_or_delayed_result": stale_or_delayed_result,
                "aimi_final_15": None if aimi_final_15 is None else round(aimi_final_15, 1),
                "aimi_final_30": None if aimi_final_30 is None else round(aimi_final_30, 1),
                "aimi_final_60": None if aimi_final_60 is None else round(aimi_final_60, 1),
                "soft_momentum_15": None if soft_momentum_15 is None else round(soft_momentum_15, 1),
                "soft_momentum_30": None if soft_momentum_30 is None else round(soft_momentum_30, 1),
                "soft_momentum_60": None if soft_momentum_60 is None else round(soft_momentum_60, 1),
                "aimi_final_error_15": None if aimi_final_error_15 is None else round(aimi_final_error_15, 1),
                "aimi_final_error_30": None if aimi_final_error_30 is None else round(aimi_final_error_30, 1),
                "forecast_error_60": None if forecast_error_60 is None else round(forecast_error_60, 1),
                "aimi_final_error_60": None if aimi_final_error_60 is None else round(aimi_final_error_60, 1),
                "soft_momentum_error_15": None if soft_momentum_error_15 is None else round(soft_momentum_error_15, 1),
                "soft_momentum_error_30": None if soft_momentum_error_30 is None else round(soft_momentum_error_30, 1),
                "soft_momentum_error_60": None if soft_momentum_error_60 is None else round(soft_momentum_error_60, 1),
                "soft_momentum_winner_60": soft_momentum_winner_60,
                "below_target_after": below_target_after,
                "hypo_after": hypo_after,
                "smb_or_high_tbr": smb_or_high_tbr,
                "late_basal_reduction": late_basal_reduction,
                "hyper_kicker": hyper_kicker,
                "hyper_kicker_blocked": hyper_kicker_blocked,
                "low_forecast_with_hyper_kicker": low_forecast_with_hyper_kicker,
            }
        )

    now_label = datetime.now().strftime("%Y%m%d-%H%M%S")
    csv_path = out_dir / f"effectiveness_decisions_{now_label}.csv"
    json_path = out_dir / f"effectiveness_summary_{now_label}.json"
    md_path = out_dir / f"effectiveness_report_{now_label}.md"
    latest_md = out_dir / "latest_report.md"
    latest_json = out_dir / "latest_summary.json"

    if decisions:
        with csv_path.open("w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=list(decisions[0].keys()))
            writer.writeheader()
            writer.writerows(decisions)

    total = len(decisions)
    rescue = [d for d in decisions if d["type"] == "быстрый спасательный отскок"]
    uam = [d for d in decisions if d["type"] == "обычная невведенная еда"]
    with_smb = [d for d in decisions if d["smb_units"] > 0]
    post_smb_low = [d for d in with_smb if d["below_target_after"] or d["hypo_after"]]
    false_high = [d for d in decisions if d["forecast_error_60"] is not None and d["forecast_error_60"] >= 25]
    rescue_false_high = [d for d in rescue if d["forecast_error_60"] is not None and d["forecast_error_60"] >= 25]
    fresh_smb_cycles = [d for d in decisions if d["fresh_smb_60m"] >= 0.2]
    fresh_smb_post_low = [d for d in fresh_smb_cycles if d["below_target_after"] or d["hypo_after"]]
    hyper_kicker_cycles = [d for d in decisions if d["hyper_kicker"]]
    hyper_kicker_blocked_cycles = [d for d in decisions if d["hyper_kicker_blocked"]]
    low_forecast_hyper_kicker = [d for d in decisions if d["low_forecast_with_hyper_kicker"]]
    late_basal_cycles = [d for d in decisions if d["late_basal_reduction"]]
    stale_or_delayed = [d for d in decisions if d["stale_or_delayed_result"]]
    stale_or_delayed_with_action = [
        d for d in stale_or_delayed
        if d["smb_units"] > 0.0 or (
            d["rate"] > 0.0
            and d["duration"] > 0.0
            and d["profile_current_basal"] > 0.0
            and d["rate"] > d["profile_current_basal"] * 1.2
        )
    ]
    falling_cycles = [d for d in decisions if d["delta"] <= -2.0 or d["short_delta"] <= -2.0]
    falling_forecast_too_low_15 = [
        d for d in falling_cycles
        if d["aimi_final_error_15"] is not None and d["aimi_final_error_15"] <= -10.0
    ]
    falling_forecast_too_low_30 = [
        d for d in falling_cycles
        if d["aimi_final_error_30"] is not None and d["aimi_final_error_30"] <= -15.0
    ]
    errors = [d["forecast_error_60"] for d in decisions if d["forecast_error_60"] is not None]
    aimi_final_abs_errors_15 = [abs(d["aimi_final_error_15"]) for d in decisions if d["aimi_final_error_15"] is not None]
    aimi_final_abs_errors_30 = [abs(d["aimi_final_error_30"]) for d in decisions if d["aimi_final_error_30"] is not None]
    aimi_final_abs_errors = [abs(d["aimi_final_error_60"]) for d in decisions if d["aimi_final_error_60"] is not None]
    soft_momentum_abs_errors = [abs(d["soft_momentum_error_60"]) for d in decisions if d["soft_momentum_error_60"] is not None]
    ab_cycles = [
        d for d in decisions
        if d["aimi_final_error_60"] is not None and d["soft_momentum_error_60"] is not None
    ]
    ab_aimi_final_abs_errors = [abs(d["aimi_final_error_60"]) for d in ab_cycles]
    ab_soft_momentum_abs_errors = [abs(d["soft_momentum_error_60"]) for d in ab_cycles]
    soft_momentum_cycles = [d for d in decisions if d["soft_momentum_60"] is not None]
    soft_momentum_pending = [
        d for d in soft_momentum_cycles
        if d["aimi_final_error_60"] is None or d["soft_momentum_error_60"] is None
    ]
    soft_momentum_better = [d for d in ab_cycles if d["soft_momentum_winner_60"]]
    aimi_final_better = [
        d for d in ab_cycles
        if abs(d["aimi_final_error_60"]) < abs(d["soft_momentum_error_60"])
    ]

    summary = {
        "created_at": datetime.now().isoformat(timespec="seconds"),
        "db": str(db),
        "analyzed_from": local_time(cutoff),
        "analyzed_to": local_time(newest_ts),
        "decisions": total,
        "fast_rescue_rebound": len(rescue),
        "ordinary_unannounced_food": len(uam),
        "smb_cycles": len(with_smb),
        "smb_followed_by_below_target_or_hypo": len(post_smb_low),
        "false_high_forecast_60m": len(false_high),
        "fast_rescue_false_high_forecast_60m": len(rescue_false_high),
        "fresh_smb_cycles": len(fresh_smb_cycles),
        "fresh_smb_followed_by_below_target_or_hypo": len(fresh_smb_post_low),
        "hyper_kicker_cycles": len(hyper_kicker_cycles),
        "hyper_kicker_blocked_cycles": len(hyper_kicker_blocked_cycles),
        "low_forecast_with_hyper_kicker": len(low_forecast_hyper_kicker),
        "late_basal_reduction_cycles": len(late_basal_cycles),
        "stale_or_delayed_results": len(stale_or_delayed),
        "stale_or_delayed_results_with_action": len(stale_or_delayed_with_action),
        "falling_cycles": len(falling_cycles),
        "falling_forecast_too_low_15m": len(falling_forecast_too_low_15),
        "falling_forecast_too_low_30m": len(falling_forecast_too_low_30),
        "median_forecast_error_60m": None if not errors else round(median(errors), 1),
        "median_abs_aimi_final_error_15m": None if not aimi_final_abs_errors_15 else round(median(aimi_final_abs_errors_15), 1),
        "median_abs_aimi_final_error_30m": None if not aimi_final_abs_errors_30 else round(median(aimi_final_abs_errors_30), 1),
        "soft_momentum_available_cycles": len(soft_momentum_cycles),
        "soft_momentum_pending_60m": len(soft_momentum_pending),
        "ab_momentum_cycles_60m": len(ab_cycles),
        "soft_momentum_better_60m": len(soft_momentum_better),
        "aimi_final_better_60m": len(aimi_final_better),
        "median_abs_aimi_final_error_60m": None if not aimi_final_abs_errors else round(median(aimi_final_abs_errors), 1),
        "median_abs_soft_momentum_error_60m": None if not soft_momentum_abs_errors else round(median(soft_momentum_abs_errors), 1),
        "median_abs_aimi_final_ab_error_60m": None if not ab_aimi_final_abs_errors else round(median(ab_aimi_final_abs_errors), 1),
        "median_abs_soft_momentum_ab_error_60m": None if not ab_soft_momentum_abs_errors else round(median(ab_soft_momentum_abs_errors), 1),
        "waiting_for_new_data_after_monitor_start": waiting_for_new_data,
    }
    json_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    latest_json.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def sample_rows(rows: list[dict[str, Any]], limit: int = 8, include_basal: bool = False) -> str:
        if not rows:
            return "Нет эпизодов.\n"
        if include_basal:
            lines = [
                "| время | тип | BG | delta | прогноз | MinGuard | цель | базал | профиль | факт +60 |",
                "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|",
            ]
        else:
            lines = [
                "| время | тип | BG | прогноз | AIMI +15 | AIMI +30 | AIMI +60 | мягк. +60 | факт +15 | факт +30 | факт +60 | цель | SMB | свежий SMB 60м | мин +180 |",
                "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
            ]
        for d in rows[:limit]:
            if include_basal:
                lines.append(
                    f"| {d['time']} | {d['type']} | {d['bg']} | {d['delta']} | {d['forecast']} | "
                    f"{d['min_guard']} | {d['target']} | {d['rate']} | {d['profile_current_basal']} | "
                    f"{'' if d['actual_60'] is None else round(d['actual_60'], 1)} |"
                )
            else:
                lines.append(
                    f"| {d['time']} | {d['type']} | {d['bg']} | {d['forecast']} | "
                    f"{'' if d['aimi_final_15'] is None else d['aimi_final_15']} | "
                    f"{'' if d['aimi_final_30'] is None else d['aimi_final_30']} | "
                    f"{'' if d['aimi_final_60'] is None else d['aimi_final_60']} | "
                    f"{'' if d['soft_momentum_60'] is None else d['soft_momentum_60']} | "
                    f"{'' if d['actual_15'] is None else round(d['actual_15'], 1)} | "
                    f"{'' if d['actual_30'] is None else round(d['actual_30'], 1)} | "
                    f"{'' if d['actual_60'] is None else round(d['actual_60'], 1)} | {d['target']} | "
                    f"{d['smb_units']} | {d['fresh_smb_60m']} | {'' if d['min_180'] is None else round(d['min_180'], 1)} |"
                )
        return "\n".join(lines) + "\n"

    proposal_lines: list[str] = []
    if waiting_for_new_data:
        proposal_lines.append(
            "- Новых APS-циклов после старта мониторинга еще нет; ниже показана базовая статистика для сравнения."
        )
    if rescue_false_high:
        proposal_lines.append(
            "- Проверить, стала ли модель быстрых углеводов короче: в быстрых спасательных отскоках прогноз все еще завышен на 60 минут."
        )
    if post_smb_low:
        proposal_lines.append(
            "- Проверить пересчет прогноза после SMB: есть циклы, где после SMB фактическая глюкоза ушла ниже цели или в гипо."
        )
    if fresh_smb_post_low:
        proposal_lines.append(
            "- Усилить учет свежего SMB: свежий SMB за последние 60 минут связан с последующим уходом ниже цели."
        )
    if low_forecast_hyper_kicker:
        proposal_lines.append(
            "- Проверить Global Hyper Kicker/TBR: есть циклы, где прогноз уже ниже цели, но включен повышающий режим базала."
        )
    if late_basal_cycles:
        proposal_lines.append(
            "- Проверить позднее снижение базала: прогнозная линия уже ниже цели, но финальный базал еще выше половины профильного."
        )
    if stale_or_delayed_with_action:
        proposal_lines.append(
            "- Усилить защиту свежести: есть APS-результаты, завершившиеся уже после новой CGM-точки, но все еще с действием SMB/TBR."
        )
    if falling_forecast_too_low_15 or falling_forecast_too_low_30:
        proposal_lines.append(
            "- Проверить слой падения: есть циклы, где прогноз при снижении оказался заметно ниже факта на +15/+30 минут."
        )
    if ab_cycles:
        proposal_lines.append(
            "- Сравнить A/B momentum: если мягкая линия чаще выигрывает на +60 минут и не дает крупных недооценок, переносить ее в основной прогноз."
        )
    elif soft_momentum_pending:
        proposal_lines.append(
            "- A/B momentum уже пишется, но фактическая глюкоза +60 минут еще не накопилась; дождаться следующего отчета после часа наблюдения."
        )
    if not proposal_lines:
        proposal_lines.append("- Явных сигналов ухудшения по текущему окну нет; продолжать сбор для накопления статистики.")

    md = f"""# Монитор эффективности AIMI

Создано: {summary['created_at']}

Период данных: {summary['analyzed_from']} - {summary['analyzed_to']}

Источник: `{db}`

## Короткий вывод

- Всего циклов APS: {summary['decisions']}
- Быстрый спасательный отскок: {summary['fast_rescue_rebound']}
- Обычная невведенная еда: {summary['ordinary_unannounced_food']}
- Циклы с SMB: {summary['smb_cycles']}
- SMB с последующим уходом ниже цели/в гипо: {summary['smb_followed_by_below_target_or_hypo']}
- Завышенный прогноз на 60 минут: {summary['false_high_forecast_60m']}
- Завышенный прогноз в быстрых спасательных отскоках: {summary['fast_rescue_false_high_forecast_60m']}
- Циклы со свежим SMB за последние 60 минут: {summary['fresh_smb_cycles']}
- Свежий SMB + последующий уход ниже цели/в гипо: {summary['fresh_smb_followed_by_below_target_or_hypo']}
- Global Hyper Kicker: {summary['hyper_kicker_cycles']}
- Global Hyper Kicker заблокирован защитой: {summary['hyper_kicker_blocked_cycles']}
- Global Hyper Kicker при прогнозе ниже цели: {summary['low_forecast_with_hyper_kicker']}
- Позднее снижение базала при низком прогнозе: {summary['late_basal_reduction_cycles']}
- Устаревшие или задержанные APS-результаты: {summary['stale_or_delayed_results']}
- Устаревшие или задержанные результаты с действием SMB/TBR: {summary['stale_or_delayed_results_with_action']}
- Циклы с падением: {summary['falling_cycles']}
- Слишком низкий прогноз при падении на 15 минут: {summary['falling_forecast_too_low_15m']}
- Слишком низкий прогноз при падении на 30 минут: {summary['falling_forecast_too_low_30m']}
- Медианная ошибка прогноза на 60 минут: {summary['median_forecast_error_60m']}
- Медианная абсолютная ошибка AIMI +15: {summary['median_abs_aimi_final_error_15m']}
- Медианная абсолютная ошибка AIMI +30: {summary['median_abs_aimi_final_error_30m']}
- Циклы с мягкой линией momentum: {summary['soft_momentum_available_cycles']}
- Циклы мягкой линии, ожидающие факт +60: {summary['soft_momentum_pending_60m']}
- A/B momentum циклов с обеими линиями: {summary['ab_momentum_cycles_60m']}
- Мягкий momentum точнее на +60 минут: {summary['soft_momentum_better_60m']}
- Основной AIMI точнее на +60 минут: {summary['aimi_final_better_60m']}
- Медианная абсолютная ошибка основного AIMI +60: {summary['median_abs_aimi_final_error_60m']}
- Медианная абсолютная ошибка мягкого momentum +60: {summary['median_abs_soft_momentum_error_60m']}
- В A/B-циклах ошибка основного AIMI +60: {summary['median_abs_aimi_final_ab_error_60m']}
- В A/B-циклах ошибка мягкого momentum +60: {summary['median_abs_soft_momentum_ab_error_60m']}

## Что смотреть после изменения

{chr(10).join(proposal_lines)}

## Эпизоды с завышенным прогнозом

{sample_rows(false_high)}

## A/B momentum: где мягкая линия была точнее

{sample_rows(soft_momentum_better)}

## Быстрые спасательные отскоки

{sample_rows(rescue)}

## SMB с последующим уходом ниже цели или в гипо

{sample_rows(post_smb_low)}

## Global Hyper Kicker при прогнозе ниже цели

{sample_rows(low_forecast_hyper_kicker)}

## Позднее снижение базала

{sample_rows(late_basal_cycles, include_basal=True)}

## Устаревшие или задержанные результаты APS

{sample_rows(stale_or_delayed_with_action)}

## Слишком низкий прогноз при падении

{sample_rows(falling_forecast_too_low_15 + falling_forecast_too_low_30)}

## Файлы

- Решения: `{csv_path}`
- Сводка JSON: `{json_path}`
"""
    md_path.write_text(md, encoding="utf-8")
    latest_md.write_text(md, encoding="utf-8")

    print(f"Отчет мониторинга: {md_path}")
    print(f"Последний отчет: {latest_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
