#!/usr/bin/env python3
"""Read-only observational audit. No dosing recommendation or patient simulation."""

from __future__ import annotations

import argparse
import bisect
import json
import math
import re
import sqlite3
from collections import Counter
from datetime import datetime
from pathlib import Path
from statistics import median
from zoneinfo import ZoneInfo

MINUTE = 60_000
TZ = ZoneInfo("Europe/Moscow")
ACTIVE = re.compile(r"Защита от раннего перелива активна:[^\n]*лимит SMB=([\d.,]+)")
LIMIT = re.compile(r"(?:SMB ограничен защитой от раннего перелива:|Финальный SMB cap: ранний перелив:[^\n]*?;)\s*([\d.,]+)\s*(?:->|→)\s*([\d.,]+)")
FINAL_SMB = re.compile(r"Final SMB:\s*([\d.,]+)")
OPTIMIZER_COST = re.compile(r"Проверка варианта оптимизатора, не подача: ([\d.eE+-]+) Е; оценка=([\d.eE+-]+)")


def number(value):
    try:
        result = float(str(value).replace(",", "."))
        return result if math.isfinite(result) else None
    except (ValueError, TypeError):
        return None


def stamp(timestamp):
    return datetime.fromtimestamp(timestamp / 1000, TZ).isoformat(timespec="seconds")


def optimizer_cost_audit(result):
    logs = result.get("consoleLog") or []
    text = logs if isinstance(logs, str) else "\n".join(logs)
    points = [(float(dose), float(cost)) for dose, cost in OPTIMIZER_COST.findall(text)]
    if len(points) < 3 or points[0][0] == points[1][0]:
        return None
    (x, y), (z, w) = points[:2]
    # Check the caller's suspected C + 0.5 * (fixed_basal - SMB_candidate)^2 defect.
    fixed_parameter = ((z * z - x * x) / 2 - (w - y)) / (z - x)
    constant = y - 0.5 * (fixed_parameter - x) ** 2
    residual = max(abs(cost - (constant + 0.5 * (fixed_parameter - dose) ** 2)) for dose, cost in points)
    return {"points": points, "inferred_fixed_parameter": fixed_parameter,
            "constant_forecast_cost": constant, "max_residual": residual,
            "matches_penalty_only": residual < 1e-7}


def parse_cap(result):
    logs = result.get("consoleLog") or []
    if isinstance(logs, str):
        logs = [logs]
    evidence = [line for line in logs if isinstance(line, str) and ACTIVE.search(line)]
    if not any(math.isclose(number(ACTIVE.search(line).group(1)) or 0, 0.3) for line in evidence):
        return None
    text = "\n".join(logs + [result.get("reason") or ""])
    limits = LIMIT.findall(text)
    candidates = [number(before) for before, after in limits if number(after) == 0.3]
    fallback = FINAL_SMB.findall(text)
    return {
        "cap_evidence": evidence[0],
        "before_cap": candidates[-1] if candidates else (number(fallback[-1]) if fallback else None),
        "before_cap_source": "explicit_limit" if candidates else ("executor_final" if fallback else "missing"),
    }


class Timeline:
    def __init__(self, rows):
        self.rows = sorted(rows, key=lambda row: row["timestamp"])
        self.times = [row["timestamp"] for row in self.rows]

    def between(self, start, end):
        return self.rows[bisect.bisect_right(self.times, start):bisect.bisect_right(self.times, end)]

    def nearest(self, time, tolerance=7.5 * MINUTE):
        i = bisect.bisect_left(self.times, time)
        candidates = self.rows[max(0, i - 1):i + 1]
        best = min(candidates, key=lambda row: abs(row["timestamp"] - time), default=None)
        return best if best and abs(best["timestamp"] - time) <= tolerance else None


def cluster(rows, gap_minutes=30):
    groups = []
    for row in rows:
        if not groups or row["timestamp"] - groups[-1][-1]["timestamp"] > gap_minutes * MINUTE:
            groups.append([])
        groups[-1].append(row)
    return groups


def window(bg, boluses, carbs, start, minutes):
    end = start + minutes * MINUTE
    points = bg.between(start, end)
    times = [start] + [point["timestamp"] for point in points] + [end]
    max_gap = max(b - a for a, b in zip(times, times[1:])) / MINUTE
    doses = boluses.between(start, end)
    food = carbs.between(start, end)
    values = [point["value"] for point in points]
    first_high = next((p for p in points if p["value"] > 180), None)
    first_low = next((p for p in points if p["value"] < 70), None)
    first_intervention = min((row["timestamp"] for row in doses + food), default=end + 1)
    at_end = bg.nearest(end)
    return {
        "complete": bool(points) and max_gap <= 15 and bool(at_end),
        "max_gap_minutes": round(max_gap, 2),
        "samples": len(points),
        "min_bg": min(values) if values else None,
        "max_bg": max(values) if values else None,
        "bg_at_end": at_end["value"] if at_end else None,
        "high_before_new_bolus_or_carbs": bool(first_high and first_high["timestamp"] < first_intervention),
        "low_before_new_bolus_or_carbs": bool(first_low and first_low["timestamp"] < first_intervention),
        "normal_bolus_u": round(sum(row["amount"] for row in doses if row["type"] != "SMB"), 4),
        "smb_u": round(sum(row["amount"] for row in doses if row["type"] == "SMB"), 4),
        "new_carbs_g": round(sum(row["amount"] for row in food), 2),
        "treatments": [{"time": stamp(row["timestamp"]), "kind": row.get("type", "carbs"), "amount": row["amount"]} for row in sorted(doses + food, key=lambda row: row["timestamp"])],
    }


def summary(episodes, minutes):
    full = [episode for episode in episodes if episode[f"next_{minutes}m"]["complete"]]
    high = [e for e in full if e[f"next_{minutes}m"]["max_bg"] > 180]
    low = [e for e in full if e[f"next_{minutes}m"]["min_bg"] < 70]
    return {
        "episodes": len(episodes), "complete_followup": len(full),
        "above_180": len(high), "below_70": len(low),
        "both": sum(e in low for e in high),
        "above_180_before_new_bolus_or_carbs": sum(e[f"next_{minutes}m"]["high_before_new_bolus_or_carbs"] for e in full),
        "below_70_before_new_bolus_or_carbs": sum(e[f"next_{minutes}m"]["low_before_new_bolus_or_carbs"] for e in full),
        "with_additional_normal_bolus": sum(e[f"next_{minutes}m"]["normal_bolus_u"] > 0 for e in full),
        "with_additional_carbs": sum(e[f"next_{minutes}m"]["new_carbs_g"] > 0 for e in full),
        "without_new_carbs_or_any_bolus": sum(not e[f"next_{minutes}m"]["treatments"] for e in full),
        "overlap_with_next_episode": sum(e["minutes_to_next_episode"] is not None and e["minutes_to_next_episode"] < minutes for e in full),
    }


def analyze(db, output):
    con = sqlite3.connect(f"{db.resolve().as_uri()}?mode=ro", uri=True)
    con.execute("PRAGMA query_only=ON")
    con.row_factory = sqlite3.Row
    valid = "isValid=1 AND referenceId IS NULL"
    bg = Timeline([dict(row) for row in con.execute(f"SELECT timestamp,value,noise FROM glucoseValues WHERE {valid} AND value>0 ORDER BY timestamp")])
    boluses = Timeline([dict(row) for row in con.execute(f"SELECT timestamp,amount,type FROM boluses WHERE {valid} AND amount>0 ORDER BY timestamp")])
    carbs = Timeline([dict(row) for row in con.execute(f"SELECT timestamp,amount,duration FROM carbs WHERE {valid} AND amount>0 ORDER BY timestamp")])
    bounds = dict(con.execute(f"SELECT count(*) AS count,min(timestamp) AS first,max(timestamp) AS last FROM apsResults WHERE {valid}").fetchone())
    rows = []
    query = f"SELECT * FROM apsResults WHERE {valid} AND (resultJson LIKE '%лимит SMB=0.30%' OR resultJson LIKE '%лимит SMB=0,30%') ORDER BY timestamp"
    for row in con.execute(query):
        result = json.loads(row["resultJson"])
        cap = parse_cap(result)
        if cap is None:
            continue
        glucose = json.loads(row["glucoseStatusJson"] or "{}")
        meal = json.loads(row["mealDataJson"] or "{}")
        profile = json.loads(row["profileJson"] or "{}")
        insulin = json.loads(row["iobDataJson"] or "[]")
        first_iob = insulin[0] if insulin else {}
        time = row["timestamp"]
        record = dict(cap, id=row["id"], timestamp=time, time=stamp(time),
            bg=number(result.get("bg", glucose.get("glucose"))), delta=number(glucose.get("delta")),
            short_delta=number(glucose.get("shortAvgDelta")), cob=number(meal.get("mealCOB")),
            isf=number(result.get("variable_sens")), iob=number(first_iob.get("iob")),
            profile_basal=number(profile.get("current_basal")),
            smb_request=number(result.get("units")), rate_request=number(result.get("rate")),
            insulin_req=number(result.get("insulinReq")), target=number(profile.get("target_bg")),
            min_guard=number(result.get("minGuardBG")), eventual=number(result.get("eventualBG")))
        if record["isf"] is None:
            record["isf"] = number(profile.get("variable_sens"))
        record["candidate_above_cap"] = cap["before_cap"] is not None and cap["before_cap"] > 0.30001
        record["cap_reduced_candidate"] = record["candidate_above_cap"] and cap["before_cap_source"] == "explicit_limit"
        record["optimizer_cost_audit"] = optimizer_cost_audit(result)
        prior = bg.between(time - 60 * MINUTE, time)
        record["prior_60m_min_bg"] = min((p["value"] for p in prior), default=None)
        record["prior_60m_max_gap"] = max((b - a for a, b in zip([time - 60 * MINUTE] + [p["timestamp"] for p in prior], [p["timestamp"] for p in prior] + [time])), default=60 * MINUTE) / MINUTE
        record["prior_60m_boluses_u"] = round(sum(b["amount"] for b in boluses.between(time - 60 * MINUTE, time)), 4)
        record["prior_60m_carbs_g"] = sum(c["amount"] for c in carbs.between(time - 60 * MINUTE, time))
        rows.append(record)
    con.close()
    groups = cluster(rows)
    episodes = []
    for i, group in enumerate(groups):
        first, last = group[0], group[-1]
        episode = dict(first, last_time=last["time"], cycles=len(group),
            cycle_ids=[r["id"] for r in group], any_candidate_reduced=any(r["cap_reduced_candidate"] for r in group),
            largest_candidate=max((r["before_cap"] for r in group if r["before_cap"] is not None), default=None),
            minutes_to_next_episode=(groups[i + 1][0]["timestamp"] - first["timestamp"]) / MINUTE if i + 1 < len(groups) else None)
        for minutes in (60, 120, 240):
            episode[f"next_{minutes}m"] = window(bg, boluses, carbs, first["timestamp"], minutes)
        episodes.append(episode)
    isfs = [r["isf"] for r in rows if r["isf"] is not None and r["isf"] > 0]
    result_summary = {
        "database": str(db.resolve()), "aps_rows": bounds["count"],
        "aps_first": stamp(bounds["first"]) if bounds["first"] is not None else None,
        "aps_last": stamp(bounds["last"]) if bounds["last"] is not None else None,
        "bg_last": stamp(bg.times[-1]) if bg.times else None,
        "cap_first": rows[0]["time"] if rows else None, "cap_last": rows[-1]["time"] if rows else None,
        "cap_cycles": len(rows), "candidate_reduced_cycles": sum(r["cap_reduced_candidate"] for r in rows),
        "candidate_above_cap_cycles": sum(r["candidate_above_cap"] for r in rows),
        "before_cap_sources": dict(Counter(r["before_cap_source"] for r in rows)),
        "zero_smb_requests": sum(r["smb_request"] == 0 for r in rows),
        "absent_smb_requests": sum(r["smb_request"] is None for r in rows),
        "positive_smb_requests": sum((r["smb_request"] or 0) > 0 for r in rows),
        "zero_basal_requests": sum(r["rate_request"] == 0 for r in rows),
        "episodes_by_month": dict(Counter(e["time"][:7] for e in episodes)),
        "isf_min_median_max": [min(isfs), median(isfs), max(isfs)] if isfs else None,
        "effect_03_isf_min_median_max": [round(0.3 * value, 2) for value in (min(isfs), median(isfs), max(isfs))] if isfs else None,
        "all_episodes": {str(m): summary(episodes, m) for m in (60, 120, 240)},
        "candidate_reduced_episodes": {str(m): summary([e for e in episodes if e["any_candidate_reduced"]], m) for m in (60, 120, 240)},
        "recent_30d_episodes": {str(m): summary([e for e in episodes if e["timestamp"] >= bounds["last"] - 30 * 1440 * MINUTE], m) for m in (60, 120, 240)},
        "clustering_sensitivity": {str(gap): len(cluster(rows, gap)) for gap in (15, 30, 60)},
        "limitations": ["No counterfactual dose response is inferred from observed BG.",
            "Recorded insulin delivery is an exposure, not proof of required insulin.",
            "No new recorded food does not rule out unannounced food.",
            "Earlier insulin and basal changes affect later outcomes; models/profiles changed over months.",
            "Follow-up windows can overlap; incomplete CGM windows are excluded from outcome counts.",
            "The parsed cap is an algorithm branch, not pump-confirmed delivery."],
    }
    output.mkdir(parents=True, exist_ok=True)
    for name, data in (("summary", result_summary), ("cycles", rows), ("episodes", episodes)):
        (output / f"{name}.json").write_text(json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(result_summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    analyze(args.db, args.out)
