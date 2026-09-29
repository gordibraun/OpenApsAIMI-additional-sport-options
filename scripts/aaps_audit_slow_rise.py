#!/usr/bin/env python3
"""Read-only extraction of recorded decisions; not a patient or dose simulation."""
import argparse
import json
import re
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path

TZ = timezone(timedelta(hours=3))
VALID = "isValid=1 AND referenceId IS NULL"
NUMBER = r"([+-]?\d+(?:\.\d+)?(?:[Ee][+-]?\d+)?)"


def stamp(ms):
    return datetime.fromtimestamp(ms / 1000, TZ).isoformat(timespec="seconds")


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(db, out, fixture):
    con = sqlite3.connect(db.resolve().as_uri() + "?mode=ro", uri=True)
    con.row_factory = sqlite3.Row
    end = con.execute(f"SELECT max(timestamp) FROM apsResults WHERE {VALID}").fetchone()[0]
    start = int(datetime.fromtimestamp(end / 1000, TZ).replace(hour=14, minute=0, second=0, microsecond=0).timestamp() * 1000)
    timeline = []
    for row in con.execute(f"SELECT * FROM apsResults WHERE {VALID} AND timestamp>=? ORDER BY timestamp", (start,)):
        r, g, m, p = [json.loads(row[key]) for key in ("resultJson", "glucoseStatusJson", "mealDataJson", "profileJson")]
        trace = r.get("decisionTrace", [])
        before = (r.get("predBGs") or {}).get("AIMI_BEFORE_DECISION", [])
        after = (r.get("predBGs") or {}).get("AIMI_FINAL", [])
        timeline.append(dict(id=row["id"], time=stamp(row["timestamp"]), bg=g["glucose"], delta=g.get("delta", 0.0),
            cob=m.get("mealCOB", 0.0), iob=r.get("IOB"), isf=r.get("variable_sens"), smb=r.get("units"),
            basal=r.get("rate"), basal_duration=r.get("duration"), carbs_required=r.get("carbsReq"),
            before60=before[12] if len(before) > 12 else None, after60=after[12] if len(after) > 12 else None,
            branches=[s for s in trace if s.get("branch")],
            evidence=[s for s in r.get("consoleLog", []) if any(k in s for k in ("Вход прогноза:", "Тип углеводов", "Уверенность в невведенной", "Parallel carb forecast", "Финальный прогноз", "AIMI FINAL:"))]))
    write(out / "timeline.json", timeline)
    treatments = {}
    for table in ("boluses", "carbs", "temporaryBasals", "bolusCalculatorResults"):
        treatments[table] = [dict(row) for row in con.execute(f"SELECT * FROM {table} WHERE {VALID} AND timestamp>=? ORDER BY timestamp", (start,))]
    write(out / "treatments.json", treatments)

    # Export minimal relative-time inputs for deterministic replay of the affected zero-COB calls.
    cases = []
    for case_id in (83506, 83507):
        row = con.execute(f"SELECT * FROM apsResults WHERE {VALID} AND id=?", (case_id,)).fetchone()
        if row is None:
            continue
        r, g, p, insulin = [json.loads(row[key]) for key in ("resultJson", "glucoseStatusJson", "profileJson", "iobDataJson")]
        logs = r["consoleLog"]
        impact = next(line for line in logs if line.startswith("Вход прогноза:"))
        observed = float(re.search("влияние еды=" + NUMBER, impact)[1])
        remaining = float(re.search("остаточный пик=" + NUMBER, impact)[1])
        assert r.get("COB", 0) == 0 and "100%" in next(s for s in logs if s.startswith("Уверенность в невведенной еде:"))
        zero = insulin[0]["time"]
        cases.append(dict(label=stamp(row["timestamp"]), bg=g["glucose"], delta=g["delta"], isf=r["variable_sens"],
            carbRatio=p["carb_ratio"], observedImpact=observed, remainingPeak=remaining,
            before=r["predBGs"]["AIMI_BEFORE_DECISION"],
            insulin=[dict(time=i["time"]-zero, activity=i.get("activity", 0.0), iob=i.get("iob", 0.0)) for i in insulin]))
    write(out / "expired-carb-forecast-inputs.json", cases)
    if fixture:
        write(fixture, cases)

    candidates = []
    week = end - 7 * 24 * 60 * 60_000
    for row in con.execute(f"SELECT timestamp,resultJson,glucoseStatusJson FROM apsResults WHERE {VALID} AND timestamp>=? AND json_extract(resultJson,'$.COB')=0 AND resultJson LIKE '%Тип углеводов, выбранный пользователем:%' ORDER BY timestamp", (week,)):
        r, g = json.loads(row["resultJson"]), json.loads(row["glucoseStatusJson"])
        if g.get("delta", 0.0) > 0:
            candidates.append(dict(time=stamp(row["timestamp"]), timestamp=row["timestamp"], bg=g["glucose"], delta=g["delta"]))
    groups = []
    for c in candidates:
        if not groups or c["timestamp"] - groups[-1][-1]["timestamp"] > 30 * 60_000:
            groups.append([])
        groups[-1].append(c)
    summary = dict(database=str(db.resolve()), aps_last=stamp(end),
        glucose_last=stamp(con.execute(f"SELECT max(timestamp) FROM glucoseValues WHERE {VALID}").fetchone()[0]),
        stale_type_candidates_7d=len(candidates), groups_30m=len(groups),
        episodes=[dict(start=g[0]["time"], end=g[-1]["time"], cycles=len(g)) for g in groups],
        caveat="Candidates share a log signature, not proof of identical code or clinical causality. Later food, boluses and basal confound observed outcomes.")
    write(out / "summary.json", summary)
    con.close()
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--fixture", type=Path)
    args = parser.parse_args()
    main(args.db, args.out, args.fixture)
