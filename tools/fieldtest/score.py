#!/usr/bin/env python3
"""
Score an Asphalt field test against a hand-labelled answer key.

Inputs (see tools/fieldtest/README.md for the full protocol):

  --truth        ground_truth.csv   every defect and known road feature on the
                                    route, with coordinates and type
  --runs         runs.csv           one row per pass: start/end time, vehicle,
                                    phone, mount
  --detections   detections.csv     events exported from the backend with
                                    export_events.sql (or EventBatch .json files)

Outputs, written to --out:

  report.json    every metric below, machine-readable
  map.html       answer key, detections and clusters on one map

Metrics:

  per pass       recall (share of expected defects detected on that pass),
                 precision (share of detections near a real defect), duplicate
                 detections, false positives per mile
  location       distance from each correct detection to its labelled defect
  type           pothole / bump / rough patch confusion matrix
  map            clusters built with the backend's DBSCAN and confidence formula,
                 scored the same way: what a customer would actually see
  sweep          how recall and precision change if the detection threshold is
                 raised, using each event's recorded accel_delta_z

Usage:
  python3 tools/fieldtest/score.py --truth ground_truth.csv --runs runs.csv \\
      --detections detections.csv --out results/
"""

import argparse
import csv
import importlib.util
import json
import os
import random
import statistics
import sys
from collections import Counter, defaultdict
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))

DEFECT_TYPES = ("pothole", "bump", "rough_patch")
TRUTH_TYPES = DEFECT_TYPES + ("feature",)
DETECTED_TYPES = ("pothole", "bump", "rough_patch", "unknown")

# Mirrors VehicleProfile.kt detectionThresholdMs2.
BASE_THRESHOLD = {"four_wheeler": 4.0, "two_wheeler": 5.0}
SWEEP_OFFSETS = (0.0, 0.5, 1.0, 1.5, 2.0)

KMH_PER_MPH = 1.609344


def load_clustering():
    """Reuse the Python port of backend/internal/clustering from the synthetic tools."""
    path = os.path.join(HERE, "..", "synthetic", "generate.py")
    spec = importlib.util.spec_from_file_location("asphalt_synthetic", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


CL = load_clustering()


# ---------------------------------------------------------------------------
# Input
# ---------------------------------------------------------------------------

class InputError(Exception):
    pass


def parse_time_ms(s, where):
    """ISO 8601. Times without an offset are read as this computer's local time."""
    try:
        dt = datetime.fromisoformat(s.strip().replace("Z", "+00:00"))
    except ValueError:
        raise InputError(f"{where}: cannot read time {s!r}; use e.g. 2026-11-08T14:05:00-05:00")
    if dt.tzinfo is None:
        dt = dt.astimezone()
    return int(dt.timestamp() * 1000)


def to_float(v):
    if v is None or str(v).strip() == "":
        return None
    return float(v)


def is_yes(v, default):
    if v is None or str(v).strip() == "":
        return default
    return str(v).strip().lower() in ("yes", "y", "true", "1")


def data_rows(path):
    with open(path, newline="", encoding="utf-8-sig") as f:
        for n, row in enumerate(csv.DictReader(f), start=2):
            row = {k.strip(): (v or "").strip() for k, v in row.items() if k}
            first = next(iter(row.values()), "")
            if not any(row.values()) or first.startswith("#"):
                continue
            yield n, row


def read_truth(path):
    truth, ids = [], set()
    for n, r in data_rows(path):
        where = f"{os.path.basename(path)} line {n}"
        t = r.get("type", "").lower()
        if t not in TRUTH_TYPES:
            raise InputError(f"{where}: type must be one of {', '.join(TRUTH_TYPES)}, got {t!r}")
        if r["id"] in ids:
            raise InputError(f"{where}: duplicate id {r['id']!r}")
        ids.add(r["id"])
        try:
            lat, lon = float(r["lat"]), float(r["lon"])
        except (KeyError, ValueError):
            raise InputError(f"{where}: lat and lon must be numbers")
        truth.append({
            "id": r["id"], "lat": lat, "lon": lon, "type": t,
            # Only defects in the wheel path count toward recall. Features never do.
            "expect": t != "feature" and is_yes(r.get("expect_hit"), True),
            "severity": r.get("severity", ""), "notes": r.get("notes", ""),
        })
    if not any(t["expect"] for t in truth):
        raise InputError(f"{path}: no defects with expect_hit=yes, so recall cannot be measured")
    return truth


def read_runs(path):
    runs = []
    for n, r in data_rows(path):
        where = f"{os.path.basename(path)} line {n}"
        start, end = parse_time_ms(r["start"], where), parse_time_ms(r["end"], where)
        if end <= start:
            raise InputError(f"{where}: end is not after start")
        vt = r.get("vehicle_type", "four_wheeler") or "four_wheeler"
        if vt not in BASE_THRESHOLD:
            raise InputError(f"{where}: vehicle_type must be four_wheeler or two_wheeler")
        runs.append({
            "run_id": r["run_id"], "start_ms": start, "end_ms": end, "vehicle_type": vt,
            "phone": r.get("phone", ""), "mount": r.get("mount", ""),
            "distance_mi": to_float(r.get("distance_mi")), "notes": r.get("notes", ""),
        })
    if not runs:
        raise InputError(f"{path}: no runs")
    runs.sort(key=lambda r: r["start_ms"])
    for a, b in zip(runs, runs[1:]):
        if b["start_ms"] < a["end_ms"]:
            raise InputError(f"runs {a['run_id']} and {b['run_id']} overlap in time")
    return runs


def normalise_event(e):
    s = e.get("sensor_summary") or {}
    return {
        "event_id": e.get("event_id", ""),
        "timestamp_ms": int(float(e["timestamp_ms"])),
        "latitude": float(e["latitude"]), "longitude": float(e["longitude"]),
        "accuracy_m": to_float(e.get("accuracy_m")),
        "intensity": to_float(e.get("intensity")) or 0.0,
        "speed_kmh": to_float(e.get("speed_kmh")),
        "anomaly_type": (e.get("anomaly_type") or "unknown").lower(),
        "vehicle_type": e.get("vehicle_type") or "four_wheeler",
        "accel_delta_z": to_float(e.get("accel_delta_z", s.get("accel_delta_z"))),
        "session_id": e.get("session_id", ""),
    }


def read_detections(paths):
    events = []
    for path in paths:
        if path.lower().endswith(".json"):
            with open(path, encoding="utf-8") as f:
                data = json.load(f)
            raw = data.get("events", []) if isinstance(data, dict) else data
        else:
            raw = [r for _, r in data_rows(path)]
        events += [normalise_event(e) for e in raw]
    seen, unique = set(), []
    for e in events:
        if e["event_id"] and e["event_id"] in seen:
            continue
        seen.add(e["event_id"])
        unique.append(e)
    return unique


# ---------------------------------------------------------------------------
# Matching
# ---------------------------------------------------------------------------

def nearest(lat, lon, items, radius_m):
    best, best_d = None, None
    for t in items:
        d = CL.haversine_m(lat, lon, t["lat"], t["lon"])
        if d <= radius_m and (best_d is None or d < best_d):
            best, best_d = t, d
    return best, best_d


def classify(lat, lon, truth, radius_m):
    """Return ("tp" | "feature" | "fp", matched truth row or None, distance)."""
    t, d = nearest(lat, lon, truth, radius_m)
    if t is None:
        return "fp", None, None
    return ("feature" if t["type"] == "feature" else "tp"), t, d


def ratio(a, b):
    return a / b if b else None


def pct(x):
    return "  n/a" if x is None else f"{x * 100:4.0f}%"


def percentile(values, p):
    if not values:
        return None
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p * (len(s) - 1))))]


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------

def score_passes(runs, dets, truth, radius_m):
    expected = [t for t in truth if t["expect"]]
    per_run, matches = [], []
    hits_per_defect = Counter()
    by_run = defaultdict(list)
    for d in dets:
        by_run[d["run_id"]].append(d)

    for run in runs:
        tp = fp = feat = 0
        seen = Counter()
        for d in by_run[run["run_id"]]:
            kind, t, dist = classify(d["latitude"], d["longitude"], truth, radius_m)
            d["match"], d["match_id"], d["match_m"] = kind, t["id"] if t else None, dist
            if kind == "tp":
                tp += 1
                seen[t["id"]] += 1
                matches.append((d, t, dist))
            elif kind == "feature":
                feat += 1
            else:
                fp += 1
        caught = [t["id"] for t in expected if t["id"] in seen]
        hits_per_defect.update(caught)
        per_run.append({
            "run_id": run["run_id"], "vehicle_type": run["vehicle_type"],
            "phone": run["phone"], "mount": run["mount"],
            "detections": tp + fp + feat, "true_positives": tp, "false_positives": fp,
            "known_features": feat, "duplicates": sum(c - 1 for c in seen.values()),
            "expected_defects": len(expected), "caught": len(caught),
            "recall": ratio(len(caught), len(expected)), "precision": ratio(tp, tp + fp),
            "distance_mi": run["distance_mi"],
            "fp_per_mile": ratio(fp, run["distance_mi"]) if run["distance_mi"] else None,
        })
    return per_run, matches, hits_per_defect


def summarise(per_run):
    caught = sum(r["caught"] for r in per_run)
    expected = sum(r["expected_defects"] for r in per_run)
    tp = sum(r["true_positives"] for r in per_run)
    fp = sum(r["false_positives"] for r in per_run)
    miles = [r["distance_mi"] for r in per_run]
    return {
        "passes": len(per_run),
        "recall_per_pass": ratio(caught, expected),
        "precision": ratio(tp, tp + fp),
        "true_positives": tp, "false_positives": fp,
        "known_features": sum(r["known_features"] for r in per_run),
        "duplicates": sum(r["duplicates"] for r in per_run),
        "fp_per_mile": ratio(fp, sum(miles)) if all(miles) else None,
    }


def score_map(dets, truth, radius_m, min_conf):
    """Cluster all detections the way the backend does and score what the map shows."""
    if not dets:
        return {"clusters": [], "shown": 0, "recall": 0.0, "precision": None}
    rng = random.Random(0)
    now_ms = max(d["timestamp_ms"] for d in dets)
    clusters = [CL.score_cluster(c, now_ms, rng)
                for c in CL.dbscan(dets, CL.CLUSTER_RADIUS_M, CL.MIN_EVENTS)]
    for c in clusters:
        kind, t, dist = classify(c["latitude"], c["longitude"], truth, radius_m)
        c.update(match=kind, match_id=t["id"] if t else None, match_m=dist,
                 shown=c["confidence"] >= min_conf)
    shown = [c for c in clusters if c["shown"]]
    expected = {t["id"] for t in truth if t["expect"]}
    found = {c["match_id"] for c in shown if c["match"] == "tp"} & expected
    tp = sum(1 for c in shown if c["match"] == "tp")
    fp = sum(1 for c in shown if c["match"] == "fp")
    return {
        "clusters": clusters, "shown": len(shown),
        "recall": ratio(len(found), len(expected)), "precision": ratio(tp, tp + fp),
        "true_positives": tp, "false_positives": fp,
        "known_features": sum(1 for c in shown if c["match"] == "feature"),
        "found_ids": sorted(found),
    }


def sweep(runs, dets, truth, radius_m, min_conf):
    rows = []
    for off in SWEEP_OFFSETS:
        kept = [dict(d) for d in dets
                if d["accel_delta_z"] is None
                or d["accel_delta_z"] >= BASE_THRESHOLD.get(d["vehicle_type"], 4.0) + off]
        per_run, _, _ = score_passes(runs, kept, truth, radius_m)
        s = summarise(per_run)
        m = score_map(kept, truth, radius_m, min_conf)
        rows.append({"offset_ms2": off, "detections": len(kept),
                     "recall_per_pass": s["recall_per_pass"], "precision": s["precision"],
                     "map_recall": m["recall"], "map_precision": m["precision"]})
    return rows


def group_summary(per_run, key):
    groups = defaultdict(list)
    for r in per_run:
        groups[r[key] or "(blank)"].append(r)
    return {k: summarise(v) for k, v in sorted(groups.items())}


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def print_report(rep):
    s, m = rep["summary"], rep["map"]
    out = []
    w = out.append
    w("")
    w("Asphalt field test")
    w("=" * 60)
    w(f"Answer key: {rep['expected_defects']} expected defects, "
      f"{rep['other_truth']} other labelled points")
    w(f"Passes: {s['passes']}   Detections scored: {rep['detections_scored']}   "
      f"Match radius: {rep['radius_m']:.0f} m")
    for note in rep["notes"]:
        w(f"  note: {note}")
    w("")
    w("Single pass (what one drive catches)")
    w(f"  Recall      {pct(s['recall_per_pass'])}   of expected defects detected per pass")
    w(f"  Precision   {pct(s['precision'])}   of detections were near a real defect")
    w(f"  False pos.  {s['false_positives']:5d}" +
      (f"   ({s['fp_per_mile']:.2f} per mile)" if s["fp_per_mile"] is not None else ""))
    w(f"  Duplicates  {s['duplicates']:5d}   extra detections of a defect on the same pass")
    w(f"  At features {s['known_features']:5d}   detections at manholes, rail crossings, etc.")
    w("")
    w(f"Map (clusters at confidence >= {rep['min_confidence']:.2f}, what a customer sees)")
    w(f"  Recall      {pct(m['recall'])}   of expected defects have a cluster")
    w(f"  Precision   {pct(m['precision'])}   of shown clusters are on a real defect")
    w(f"  Clusters    {m['shown']:5d} shown of {len(m['clusters'])} formed")
    w("")
    g = rep["location_error_m"]
    if g["median"] is not None:
        w(f"Location error  median {g['median']:.1f} m   90th percentile {g['p90']:.1f} m")
        w("")
    w("Per pass")
    w(f"  {'run':<10}{'vehicle':<14}{'phone':<18}{'recall':>7}{'prec.':>7}{'FP':>5}{'dup':>5}")
    for r in rep["per_run"]:
        w(f"  {r['run_id']:<10}{r['vehicle_type']:<14}{r['phone'][:17]:<18}"
          f"{pct(r['recall']):>7}{pct(r['precision']):>7}{r['false_positives']:>5}{r['duplicates']:>5}")
    if len(rep["by_phone"]) > 1 or len(rep["by_vehicle"]) > 1:
        w("")
        w("By phone / vehicle")
        for label, group in (("phone", rep["by_phone"]), ("vehicle", rep["by_vehicle"])):
            for k, v in group.items():
                w(f"  {label:<8}{k[:24]:<26}recall {pct(v['recall_per_pass'])}"
                  f"   precision {pct(v['precision'])}   passes {v['passes']}")
    w("")
    w("Type agreement (rows = labelled, columns = detected)")
    w(f"  {'':<13}" + "".join(f"{t:>12}" for t in DETECTED_TYPES))
    for t in DEFECT_TYPES:
        row = rep["type_confusion"].get(t, {})
        w(f"  {t:<13}" + "".join(f"{row.get(d, 0):>12}" for d in DETECTED_TYPES))
    if rep["type_accuracy"] is not None:
        w(f"  Type accuracy {pct(rep['type_accuracy'])}")
    w("")
    missed = [d for d in rep["per_defect"] if d["hit_rate"] < 0.5]
    if missed:
        w("Most-missed defects (caught on fewer than half of passes)")
        for d in missed[:10]:
            w(f"  {d['id']:<10}{d['type']:<12}caught {d['hits']}/{d['passes']}   {d['notes'][:40]}")
        w("")
    w("Threshold sweep (raising the detection threshold above the SDK default)")
    w(f"  {'+m/s^2':>7}{'events':>8}{'recall':>8}{'prec.':>7}{'map rec.':>10}{'map prec.':>11}")
    for r in rep["sweep"]:
        w(f"  {r['offset_ms2']:>7.1f}{r['detections']:>8}{pct(r['recall_per_pass']):>8}"
          f"{pct(r['precision']):>7}{pct(r['map_recall']):>10}{pct(r['map_precision']):>11}")
    w("")
    print("\n".join(out))


MAP_TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Field Test Map</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css">
<script src="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js"></script>
<style>
  :root { --bg: #ffffff; --fg: #1f2937; --muted: #6b7280; --panel: rgba(255,255,255,0.94); }
  @media (prefers-color-scheme: dark) {
    :root:not([data-theme="light"]) { --bg: #111827; --fg: #e5e7eb; --muted: #9ca3af; --panel: rgba(17,24,39,0.94); }
  }
  html, body { margin: 0; height: 100%; background: var(--bg); color: var(--fg);
    font: 14px/1.4 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  #map { position: absolute; inset: 0; }
  .panel { position: absolute; z-index: 1000; top: 12px; right: 12px; max-width: min(300px, calc(100% - 64px));
    background: var(--panel); padding: 12px 14px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.2); }
  .panel h1 { font-size: 15px; margin: 0 0 6px; }
  .panel p { margin: 2px 0; color: var(--muted); }
  .key { display: flex; align-items: center; gap: 8px; margin: 3px 0; }
  .dot { width: 12px; height: 12px; border-radius: 50%; flex: none; border: 2px solid transparent; box-sizing: border-box; }
</style>
</head>
<body>
<div id="map"></div>
<div class="panel">
  <h1>Field test</h1>
  <p id="summary"></p>
  <div class="key"><span class="dot" style="background:#16a34a"></span>Defect caught on every pass</div>
  <div class="key"><span class="dot" style="background:#f59e0b"></span>Defect caught on some passes</div>
  <div class="key"><span class="dot" style="background:#dc2626"></span>Defect never caught</div>
  <div class="key"><span class="dot" style="border-color:#6b7280"></span>Not in wheel path / known feature</div>
  <div class="key"><span class="dot" style="background:#2563eb;width:8px;height:8px"></span>Detection near a defect</div>
  <div class="key"><span class="dot" style="background:#db2777;width:8px;height:8px"></span>False positive</div>
  <div class="key"><span class="dot" style="border-color:#7c3aed"></span>Cluster shown on map (30 m)</div>
</div>
<script>
const DATA = __DATA__;
const map = L.map('map');
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 20, attribution: '&copy; OpenStreetMap contributors'
}).addTo(map);
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const pct = x => x == null ? 'n/a' : Math.round(x * 100) + '%';
const s = DATA.summary;
document.getElementById('summary').textContent =
  `${s.passes} passes · recall ${pct(s.recall_per_pass)} · precision ${pct(s.precision)} · map recall ${pct(DATA.map.recall)}`;
const bounds = [];
for (const c of DATA.map.clusters.filter(c => c.shown)) {
  L.circle([c.latitude, c.longitude], { radius: 30, color: '#7c3aed', weight: 1.5, fillOpacity: 0.06 })
    .bindPopup(`<b>Cluster</b> (${esc(c.match)})<br>${esc(c.anomaly_type)} · ${c.event_count} events<br>confidence ${c.confidence.toFixed(2)}`)
    .addTo(map);
}
for (const t of DATA.truth) {
  const rate = t.hit_rate;
  const fill = !t.expect ? null : rate >= 1 ? '#16a34a' : rate > 0 ? '#f59e0b' : '#dc2626';
  L.circleMarker([t.lat, t.lon], {
    radius: 8, color: fill ? '#ffffff' : '#6b7280', weight: 2,
    fillColor: fill || '#6b7280', fillOpacity: fill ? 0.95 : 0.1
  }).bindPopup(`<b>${esc(t.id)}</b> · ${esc(t.type)}${t.expect ? '' : ' (not counted)'}<br>` +
    (t.expect ? `caught ${t.hits}/${DATA.summary.passes} passes<br>` : '') + esc(t.notes))
    .addTo(map);
  bounds.push([t.lat, t.lon]);
}
for (const d of DATA.detections) {
  const color = d.match === 'tp' ? '#2563eb' : d.match === 'fp' ? '#db2777' : '#6b7280';
  L.circleMarker([d.latitude, d.longitude], { radius: 4, stroke: false, fillColor: color, fillOpacity: 0.9 })
    .bindPopup(`<b>${esc(d.anomaly_type)}</b> · run ${esc(d.run_id)}<br>` +
      (d.match_id ? `${d.match_m.toFixed(1)} m from ${esc(d.match_id)}<br>` : 'no labelled point within radius<br>') +
      `delta ${d.accel_delta_z ?? 'n/a'} m/s² · ${d.speed_kmh == null ? 'n/a' : (d.speed_kmh / 1.609344).toFixed(0) + ' mph'}`)
    .addTo(map);
  bounds.push([d.latitude, d.longitude]);
}
if (bounds.length) map.fitBounds(bounds, { padding: [30, 30] }); else map.setView([39.8, -98.6], 4);
</script>
</body>
</html>
"""


def write_map(path, rep, truth, dets):
    hits = {d["id"]: d for d in rep["per_defect"]}
    data = {
        "summary": rep["summary"], "map": {"recall": rep["map"]["recall"], "clusters": rep["map"]["clusters"]},
        "truth": [dict(t, hits=hits.get(t["id"], {}).get("hits", 0),
                       hit_rate=hits.get(t["id"], {}).get("hit_rate", 0)) for t in truth],
        "detections": [{k: d.get(k) for k in ("latitude", "longitude", "anomaly_type", "run_id", "match",
                                              "match_id", "match_m", "accel_delta_z", "speed_kmh")}
                       for d in dets],
    }
    blob = json.dumps(data, separators=(",", ":")).replace("</", "<\\/")
    with open(path, "w", encoding="utf-8") as f:
        f.write(MAP_TEMPLATE.replace("__DATA__", blob))


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def run(args):
    truth = read_truth(args.truth)
    runs = read_runs(args.runs)
    dets = read_detections(args.detections)
    notes = []

    for d in dets:
        for r in runs:
            if r["start_ms"] <= d["timestamp_ms"] <= r["end_ms"]:
                d["run_id"], d["run_vehicle"] = r["run_id"], r["vehicle_type"]
                break
    outside = [d for d in dets if "run_id" not in d]
    dets = [d for d in dets if "run_id" in d]
    if outside:
        notes.append(f"{len(outside)} detections fall outside every run's time window and were ignored")
    mismatched = sum(1 for d in dets if d["vehicle_type"] != d["run_vehicle"])
    if mismatched:
        notes.append(f"{mismatched} detections have a vehicle_type that differs from their run's")

    if args.min_speed_mph > 0:
        gate = args.min_speed_mph * KMH_PER_MPH
        slow = [d for d in dets if d["speed_kmh"] is not None and d["speed_kmh"] < gate]
        dets = [d for d in dets if not (d["speed_kmh"] is not None and d["speed_kmh"] < gate)]
        if slow:
            notes.append(f"{len(slow)} detections below {args.min_speed_mph:g} mph ignored "
                         f"(release speed gate); use --min-speed-mph 0 to keep them")
    if len(runs) < 3:
        notes.append("fewer than 3 passes: a defect needs 3 detections to form a map cluster")

    per_run, matches, hits = score_passes(runs, dets, truth, args.radius)
    dists = [m[2] for m in matches]
    confusion = defaultdict(Counter)
    for d, t, _ in matches:
        confusion[t["type"]][d["anomaly_type"]] += 1
    typed = [(d, t) for d, t, _ in matches if t["type"] in DEFECT_TYPES]
    per_defect = sorted(
        ({"id": t["id"], "type": t["type"], "notes": t["notes"], "hits": hits[t["id"]],
          "passes": len(runs), "hit_rate": hits[t["id"]] / len(runs)}
         for t in truth if t["expect"]),
        key=lambda x: (x["hit_rate"], x["id"]))

    report = {
        "radius_m": args.radius, "min_confidence": args.min_confidence,
        "expected_defects": sum(1 for t in truth if t["expect"]),
        "other_truth": sum(1 for t in truth if not t["expect"]),
        "detections_scored": len(dets), "notes": notes,
        "summary": summarise(per_run), "per_run": per_run,
        "by_phone": group_summary(per_run, "phone"),
        "by_vehicle": group_summary(per_run, "vehicle_type"),
        "location_error_m": {"median": statistics.median(dists) if dists else None,
                             "p90": percentile(dists, 0.9), "n": len(dists)},
        "type_confusion": {k: dict(v) for k, v in confusion.items()},
        "type_accuracy": ratio(sum(1 for d, t in typed if d["anomaly_type"] == t["type"]), len(typed)),
        "per_defect": per_defect,
        "map": score_map(dets, truth, args.radius, args.min_confidence),
        "sweep": sweep(runs, dets, truth, args.radius, args.min_confidence),
    }

    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "report.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, indent=1, default=str)
    write_map(os.path.join(args.out, "map.html"), report, truth, dets)
    print_report(report)
    print(f"Wrote {os.path.join(args.out, 'report.json')} and {os.path.join(args.out, 'map.html')}")
    return report


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--truth", required=True, help="ground truth CSV")
    ap.add_argument("--runs", required=True, help="runs CSV")
    ap.add_argument("--detections", required=True, nargs="+", help="detections CSV or EventBatch JSON files")
    ap.add_argument("--out", default="fieldtest-results", help="output directory")
    ap.add_argument("--radius", type=float, default=25.0,
                    help="metres within which a detection counts as the labelled point (default 25)")
    ap.add_argument("--min-confidence", type=float, default=0.30,
                    help="cluster confidence the map shows (default 0.30, the tile API default)")
    ap.add_argument("--min-speed-mph", type=float, default=9.0,
                    help="ignore detections below this speed, matching the release build (default 9; 0 keeps all)")
    args = ap.parse_args(argv)
    try:
        run(args)
    except KeyError as e:
        sys.exit(f"error: missing column {e}")
    except (InputError, FileNotFoundError) as e:
        sys.exit(f"error: {e}")


if __name__ == "__main__":
    main()
