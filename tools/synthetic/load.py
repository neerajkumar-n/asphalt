#!/usr/bin/env python3
"""
Load the synthetic batches into an Asphalt backend so the live dashboard at
/dashboard.html fills up. Two ways, both standard library only:

1. Through the ingestion API (exercises the real pipeline):

     python3 tools/synthetic/load.py --url http://localhost:8080

   The ingestion API rejects events older than 7 days (a phone uploads within
   days, never months later), so this mode compresses the 90-day timeline into
   the last 6.5 days, keeping order. Every event is accepted, but the history
   looks a week long.

2. As SQL straight into Postgres (keeps the full 90-day history, as if the
   backend had been ingesting for three months):

     python3 tools/synthetic/load.py --sql | psql "$DATABASE_URL"

   Timestamps are written relative to NOW() at load time, so the data is always
   fresh, and ingested_at mirrors each event's time so "events in the last 24h"
   on the dashboard is realistic. Start the server once first so the schema
   exists.

Either way, the backend clusters at most 5000 unclustered events per run and
gives every run fresh cluster IDs, so load the whole set before the next
clustering run picks it up (the default dataset is under 5000 events). Loading
twice is harmless: event and batch IDs are deduplicated.
"""

import argparse
import glob
import json
import os
import sys
import time
import urllib.error
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DAY_MS = 24 * 60 * 60 * 1000


def load_batches(directory):
    files = sorted(glob.glob(os.path.join(directory, "*.json")))
    if not files:
        sys.exit(f"no batch files in {directory}; run tools/synthetic/generate.py first")
    return files, [json.load(open(f)) for f in files]


def via_api(url, files, batches):
    events = [e for b in batches for e in b["events"]]
    newest = max(e["timestamp_ms"] for e in events)
    oldest = min(e["timestamp_ms"] for e in events)
    now = int(time.time() * 1000)
    target_end = now - 5 * 60 * 1000
    target_span = int(6.5 * DAY_MS)
    scale = min(1.0, target_span / max(1, newest - oldest))

    endpoint = url.rstrip("/") + "/v1/ingest/batch"
    accepted = 0
    for f, b in zip(files, batches):
        for e in b["events"]:
            e["timestamp_ms"] = target_end - int((newest - e["timestamp_ms"]) * scale)
        b["submitted_at_ms"] = max(e["timestamp_ms"] for e in b["events"]) + 60_000
        req = urllib.request.Request(endpoint, data=json.dumps(b).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                r = json.load(resp)
        except urllib.error.HTTPError as err:
            sys.exit(f"{os.path.basename(f)}: HTTP {err.code} {err.read().decode(errors='replace')}")
        except urllib.error.URLError as err:
            sys.exit(f"cannot reach {endpoint}: {err.reason}")
        if r.get("duplicate"):
            print(f"{os.path.basename(f)}: already loaded, skipped")
            continue
        accepted += r.get("accepted_count", 0)
        print(f"{os.path.basename(f)}: {r.get('accepted_count', 0)}/{len(b['events'])} accepted")

    print(f"done: {accepted} events accepted. Clusters appear after the next clustering "
          "run (CLUSTER_INTERVAL).", file=sys.stderr)


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return repr(v)
    return "'" + str(v).replace("'", "''") + "'"


def as_sql(batches):
    events = [e for b in batches for e in b["events"]]
    newest = max(e["timestamp_ms"] for e in events)
    out = sys.stdout
    out.write("-- Asphalt synthetic seed. Timestamps are relative to NOW() at load time.\n")
    out.write("BEGIN;\n")
    out.write("CREATE TEMP TABLE _seed_now AS SELECT (EXTRACT(EPOCH FROM NOW()) * 1000)::BIGINT - 300000 AS ms;\n")
    cols = ("event_id, timestamp_ms, latitude, longitude, accuracy_m, intensity, speed_kmh, "
            "anomaly_type, vehicle_type, accel_peak_z, accel_baseline_z, accel_delta_z, "
            "gyro_peak_magnitude, sample_count, window_duration_ms, platform, sdk_int, "
            "manufacturer, model, sensor_vendor, sdk_version, session_id, ingested_at")
    for i in range(0, len(events), 500):
        rows = []
        for e in events[i:i + 500]:
            s, d = e["sensor_summary"], e["device_meta"]
            age = newest - e["timestamp_ms"]
            ts = f"(SELECT ms FROM _seed_now) - {age}"
            # Phones upload in batches, so ingestion lags detection by minutes.
            ingested = f"TO_TIMESTAMP(({ts} + {600_000 + (age % 1_800_000)}) / 1000.0)"
            vals = [q(e["event_id"]), ts, q(e["latitude"]), q(e["longitude"]), q(e.get("accuracy_m")),
                    q(e["intensity"]), q(e["speed_kmh"]), q(e["anomaly_type"]), q(e["vehicle_type"]),
                    q(s["accel_peak_z"]), q(s["accel_baseline_z"]), q(s.get("accel_delta_z")),
                    q(s["gyro_peak_magnitude"]), q(s.get("sample_count")), q(s["window_duration_ms"]),
                    q(d["platform"]), q(d["sdk_int"]), q(d.get("manufacturer")), q(d.get("model")),
                    q(d.get("sensor_vendor")), q(e["sdk_version"]), q(e.get("session_id")), ingested]
            rows.append("(" + ", ".join(vals) + ")")
        out.write(f"INSERT INTO road_events ({cols}) VALUES\n" + ",\n".join(rows) +
                  "\nON CONFLICT (event_id) DO NOTHING;\n")
    out.write("COMMIT;\n")
    print(f"-- {len(events)} events", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://localhost:8080", help="backend base URL (API mode)")
    ap.add_argument("--sql", action="store_true", help="print SQL for psql instead of calling the API")
    ap.add_argument("--dir", default=os.path.join(ROOT, "data", "synthetic", "batches"))
    args = ap.parse_args()

    files, batches = load_batches(args.dir)
    if args.sql:
        as_sql(batches)
    else:
        via_api(args.url, files, batches)


if __name__ == "__main__":
    main()
