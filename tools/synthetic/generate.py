#!/usr/bin/env python3
"""
Generate a synthetic Asphalt dataset for demos.

Produces two things:

  data/synthetic/batches/*.json   EventBatch payloads that validate against
                                  contracts/batch.schema.json and can be POSTed
                                  to /v1/ingest/batch (see load.py).

  docs/demo/data/*.json           Pre-computed clusters, stats and a lightweight
                                  event list for the static demo map in
                                  docs/demo/index.html (runs on GitHub Pages
                                  without the backend).

Clusters for the static map are computed with a Python port of
backend/internal/clustering (DBSCAN eps=30m, minPts=2, same confidence
formula), so the static page shows what the real backend would produce.

Road corridors are hand-traced waypoints along real arterial roads and
freeways in New York City, Chicago and Los Angeles. They are approximate: a point can sit a few
tens of metres off the carriageway. Everything here is synthetic.

Usage:
  python3 tools/synthetic/generate.py            # default seed, writes files
  python3 tools/synthetic/generate.py --seed 7   # different but reproducible
"""

import argparse
import json
import math
import os
import random
import uuid
from datetime import datetime, timezone

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

DAY_MS = 24 * 60 * 60 * 1000
MAX_AGE_MS = 90 * DAY_MS          # clustering.MaxAgeMs
CLUSTER_RADIUS_M = 30.0           # clustering.ClusterRadius
MIN_EVENTS = 2                    # clustering.MinEventsForConfidence
SDK_VERSION = "1.0.0"

# ---------------------------------------------------------------------------
# Cities and road corridors (lat, lon waypoints along real roads)
# ---------------------------------------------------------------------------

CITIES = {
    "nyc": {
        "name": "New York City",
        "center": [40.7800, -73.9400],
        "zoom": 11,
        "utc_offset_h": -4,     # EDT
        "defects": 200,
        # Share of reports by vehicle type. US traffic is overwhelmingly cars;
        # NYC has more two-wheelers than most US cities thanks to delivery mopeds.
        # Three-wheelers are effectively absent on US roads.
        "vehicle_mix": {"four_wheeler": 0.85, "two_wheeler": 0.15, "three_wheeler": 0.0},
        "corridors": {
            "Broadway": [(40.7047, -74.0132), (40.7359, -73.9911), (40.7580, -73.9855),
                         (40.7681, -73.9819), (40.7887, -73.9767), (40.8081, -73.9641)],
            "FDR Drive": [(40.7090, -73.9990), (40.7280, -73.9720), (40.7430, -73.9710),
                          (40.7610, -73.9570), (40.7800, -73.9440)],
            "Brooklyn-Queens Expressway": [(40.6860, -74.0000), (40.6990, -73.9900), (40.7100, -73.9580),
                                           (40.7250, -73.9380), (40.7380, -73.9060)],
            "Atlantic Avenue": [(40.6905, -73.9960), (40.6840, -73.9780), (40.6780, -73.9440),
                                (40.6760, -73.9050)],
            "Queens Boulevard": [(40.7430, -73.9230), (40.7370, -73.8780), (40.7210, -73.8440),
                                 (40.7143, -73.8310)],
            "Grand Concourse": [(40.8190, -73.9280), (40.8275, -73.9225), (40.8450, -73.9130),
                                (40.8620, -73.8990), (40.8790, -73.8840)],
        },
        # Stretches with notoriously bad surfaces get extra, stronger defects.
        "hotspots": ["Brooklyn-Queens Expressway", "Grand Concourse", "Atlantic Avenue"],
    },
    "chicago": {
        "name": "Chicago",
        "center": [41.8800, -87.6700],
        "zoom": 12,
        "utc_offset_h": -5,     # CDT
        "defects": 175,
        "vehicle_mix": {"four_wheeler": 0.92, "two_wheeler": 0.08, "three_wheeler": 0.0},
        "corridors": {
            "Lake Shore Drive": [(41.8680, -87.6150), (41.8920, -87.6140), (41.9150, -87.6260),
                                 (41.9400, -87.6380), (41.9700, -87.6480)],
            "Dan Ryan Expressway": [(41.8600, -87.6390), (41.8200, -87.6310), (41.7800, -87.6280),
                                    (41.7400, -87.6250)],
            "Kennedy Expressway": [(41.8850, -87.6450), (41.9100, -87.6660), (41.9350, -87.7000),
                                   (41.9600, -87.7400)],
            "Eisenhower Expressway": [(41.8750, -87.6400), (41.8750, -87.6900), (41.8760, -87.7400),
                                      (41.8770, -87.7800)],
            "Western Avenue": [(41.8000, -87.6840), (41.8500, -87.6860), (41.9000, -87.6870),
                               (41.9500, -87.6880), (41.9800, -87.6890)],
            "Ashland Avenue": [(41.8000, -87.6650), (41.8500, -87.6660), (41.9000, -87.6670),
                               (41.9500, -87.6690)],
            "Cicero Avenue": [(41.7800, -87.7430), (41.8300, -87.7440), (41.8800, -87.7450),
                              (41.9300, -87.7460)],
        },
        "hotspots": ["Dan Ryan Expressway", "Western Avenue", "Kennedy Expressway"],
    },
    "la": {
        "name": "Los Angeles",
        "center": [34.0450, -118.3500],
        "zoom": 11,
        "utc_offset_h": -7,     # PDT
        "defects": 165,
        "vehicle_mix": {"four_wheeler": 0.88, "two_wheeler": 0.12, "three_wheeler": 0.0},
        "corridors": {
            "I-405 San Diego Freeway": [(33.9454, -118.3701), (33.9900, -118.4050), (34.0350, -118.4400),
                                        (34.0700, -118.4600), (34.1300, -118.4750)],
            "I-10 Santa Monica Freeway": [(34.0130, -118.4900), (34.0300, -118.4000), (34.0350, -118.3450),
                                          (34.0280, -118.2800), (34.0350, -118.2400)],
            "Wilshire Boulevard": [(34.0480, -118.2600), (34.0620, -118.3100), (34.0620, -118.3500),
                                   (34.0640, -118.4000), (34.0550, -118.4450)],
            "Sunset Boulevard": [(34.0780, -118.2600), (34.0980, -118.3100), (34.0970, -118.3600),
                                 (34.0900, -118.3900), (34.0800, -118.4300)],
            "Figueroa Street": [(34.0600, -118.2500), (34.0300, -118.2700), (34.0000, -118.2820),
                                (33.9600, -118.2820)],
            "Crenshaw Boulevard": [(34.0617, -118.3265), (34.0200, -118.3350), (33.9900, -118.3300),
                                   (33.9500, -118.3270)],
        },
        "hotspots": ["Crenshaw Boulevard", "Figueroa Street", "I-10 Santa Monica Freeway"],
    },
}

# ---------------------------------------------------------------------------
# Sensor model, mirroring sdk/.../detection/VehicleProfile.kt thresholds
# ---------------------------------------------------------------------------

PROFILES = {
    #                 z-delta threshold, gyro threshold, typical speed range (km/h)
    # US urban speeds: city streets at 25-35 mph, freeway stretches faster.
    "four_wheeler":  {"delta": 4.0, "gyro": 0.30, "speed": (20, 95)},
    "two_wheeler":   {"delta": 5.0, "gyro": 0.45, "speed": (20, 80)},
    "three_wheeler": {"delta": 5.5, "gyro": 0.55, "speed": (12, 35)},
}

VEHICLE_WEIGHT = {"four_wheeler": 1.0, "two_wheeler": 0.8, "three_wheeler": 0.7}

INTENSITY = {  # base intensity range per true defect type
    "pothole": (0.50, 0.95),
    "bump": (0.35, 0.70),
    "rough_patch": (0.20, 0.45),
}

# Device metadata is kept to the two fields the event schema requires. Phone
# make, model and sensor vendor are optional and unused until per-device
# calibration exists, so the MVP dataset leaves them out.
DEVICE_META = {"platform": "android", "sdk_int": 33}


# ---------------------------------------------------------------------------
# Geometry helpers
# ---------------------------------------------------------------------------

def haversine_m(lat1, lon1, lat2, lon2):
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def offset_m(lat, lon, north_m, east_m):
    dlat = north_m / 111_320.0
    dlon = east_m / (111_320.0 * math.cos(math.radians(lat)))
    return lat + dlat, lon + dlon


def corridor_length(pts):
    return sum(haversine_m(*pts[i], *pts[i + 1]) for i in range(len(pts) - 1))


def point_along(pts, d):
    for i in range(len(pts) - 1):
        seg = haversine_m(*pts[i], *pts[i + 1])
        if d <= seg:
            t = d / seg if seg else 0
            return (pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t,
                    pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t)
        d -= seg
    return pts[-1]


# ---------------------------------------------------------------------------
# Defect and event synthesis
# ---------------------------------------------------------------------------

def pick(rng, weights):
    """Weighted choice; keys with zero weight are never returned."""
    x = rng.random() * sum(weights.values())
    last = None
    for k, w in weights.items():
        if w <= 0:
            continue
        last = k
        x -= w
        if x <= 0:
            return k
    return last


def make_defects(rng, city_key, city):
    """Place true defects along corridors, at least 80 m apart so that two
    neighbouring defects never chain into one DBSCAN cluster."""
    lengths = {name: corridor_length(p) for name, p in city["corridors"].items()}
    weights = {n: l * (1.8 if n in city["hotspots"] else 1.0) for n, l in lengths.items()}
    defects = []
    attempts = 0
    while len(defects) < city["defects"] and attempts < city["defects"] * 40:
        attempts += 1
        road = pick(rng, weights)
        lat, lon = point_along(city["corridors"][road], rng.random() * lengths[road])
        # Lateral offset of a few metres, as defects sit across a carriageway.
        lat, lon = offset_m(lat, lon, rng.gauss(0, 4), rng.gauss(0, 4))
        if any(haversine_m(lat, lon, d["lat"], d["lon"]) < 80 for d in defects):
            continue
        dtype = pick(rng, {"pothole": 0.57, "bump": 0.27, "rough_patch": 0.16})
        hot = road in city["hotspots"]
        # Report-count tiers drive the visible confidence spread on the map.
        # The auto-only tier needs three-wheelers; cities without them fold it into "weak".
        has_autos = city["vehicle_mix"].get("three_wheeler", 0) > 0
        tier = pick(rng, {"weak": 0.22 if has_autos else 0.28, "auto_only": 0.06 if has_autos else 0.0,
                          "mid": 0.52,
                          "strong": 0.16 if not hot else 0.24, "showcase": 0.04 if not hot else 0.08})
        severity = rng.uniform(*INTENSITY[dtype])
        # Most defects are fresh; some were last reported long ago so recency decays.
        stale = rng.random() < 0.12
        defects.append({
            "id": f"{city_key}-{len(defects):04d}",
            "city": city_key, "road": road, "lat": lat, "lon": lon,
            "type": dtype, "tier": tier, "severity": severity, "stale": stale,
        })
    return defects


def report_count(rng, tier):
    return {
        # The backend's DBSCAN needs 3 events for the smallest cluster.
        "weak": lambda: rng.randint(3, 4),
        "auto_only": lambda: 3,                 # < 4 so the auto-only penalty applies
        "mid": lambda: rng.randint(4, 9),
        "strong": lambda: rng.randint(10, 16),
        "showcase": lambda: rng.randint(20, 32),
    }[tier]()


def vehicles_for(rng, defect, n, mix):
    if defect["tier"] == "auto_only":
        return ["three_wheeler"] * n
    if defect["tier"] == "weak":
        return [pick(rng, mix)] * n            # single vehicle type, no cross-confirmation
    vs = [pick(rng, mix) for _ in range(n)]
    if defect["tier"] == "showcase":            # guaranteed every vehicle type the city has
        present = sorted(v for v, w in mix.items() if w > 0)
        vs[:len(present)] = present
    return vs


def event_time(rng, now_ms, stale, utc_offset_h):
    """Timestamp in local commute peaks, recent-weighted, within 90 days."""
    if stale:
        days_ago = rng.uniform(55, 88)
    else:
        days_ago = rng.expovariate(1 / 28.0)
        if days_ago > 88:
            days_ago = rng.uniform(0, 88)
    day_start = now_ms - int(days_ago) * DAY_MS
    offset_ms = utc_offset_h * 3_600_000
    day_start -= (day_start + offset_ms) % DAY_MS               # local midnight
    hour = pick(rng, {"am": 0.42, "pm": 0.43, "mid": 0.15})
    h = {"am": rng.uniform(7, 9.5), "pm": rng.uniform(16, 19), "mid": rng.uniform(9.5, 16)}[hour]
    ts = day_start + int(h * 3_600_000)
    if ts > now_ms:             # today's commute hasn't happened yet
        ts -= DAY_MS
    return ts


def sensor_summary(rng, vehicle, intensity):
    p = PROFILES[vehicle]
    baseline = rng.uniform(9.70, 9.90)
    delta = p["delta"] * (1.0 + intensity * rng.uniform(0.9, 1.4))
    window = rng.choice([240, 280, 320, 360, 400, 480, 560])
    return {
        "accel_peak_z": round(baseline + delta, 3),
        "accel_baseline_z": round(baseline, 3),
        "accel_delta_z": round(delta, 3),
        "gyro_peak_magnitude": round(p["gyro"] * (1.1 + intensity * rng.uniform(1.0, 1.8)), 3),
        "sample_count": window // 20,           # ~50 Hz
        "window_duration_ms": window,
    }


def make_event(rng, lat, lon, ts, vehicle, atype, intensity, session_id):
    lo, hi = PROFILES[vehicle]["speed"]
    return {
        "event_id": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
        "timestamp_ms": ts,
        "latitude": round(lat, 7),
        "longitude": round(lon, 7),
        "accuracy_m": round(min(48.0, rng.lognormvariate(math.log(7), 0.45)), 1),
        "intensity": round(max(0.0, min(1.0, intensity)), 3),
        "speed_kmh": round(rng.uniform(lo, hi), 1),
        "anomaly_type": atype,
        "vehicle_type": vehicle,
        "sensor_summary": sensor_summary(rng, vehicle, intensity),
        "device_meta": dict(DEVICE_META),
        "sdk_version": SDK_VERSION,
        "session_id": session_id,
    }


class Sessions:
    """Groups events into anonymous drive sessions: one vehicle type, one
    day, 3-40 events."""

    def __init__(self, rng):
        self.rng = rng
        self.open = {}

    def get(self, city, vehicle, ts):
        key = (city, vehicle, ts // DAY_MS, self.rng.randint(0, 5))
        s = self.open.get(key)
        if s is None or s["left"] <= 0:
            s = {"id": str(uuid.UUID(int=self.rng.getrandbits(128), version=4)),
                 "left": self.rng.randint(3, 40)}
            self.open[key] = s
        s["left"] -= 1
        return s["id"]


def make_events(rng, defects, city_key, city, now_ms, sessions):
    events = []
    for d in defects:
        n = report_count(rng, d["tier"])
        for vehicle in vehicles_for(rng, d, n, city["vehicle_mix"]):
            # GPS scatter around the true defect, kept well inside the 30 m eps.
            dist = min(12.0, abs(rng.gauss(0, 5)))
            ang = rng.uniform(0, 2 * math.pi)
            lat, lon = offset_m(d["lat"], d["lon"], dist * math.cos(ang), dist * math.sin(ang))
            # Some reports disagree on the type (bump read as pothole, etc.).
            atype = d["type"] if rng.random() > 0.14 else rng.choice(
                ["pothole", "bump", "rough_patch", "unknown"])
            intensity = d["severity"] + rng.gauss(0, 0.07)
            if vehicle == "three_wheeler":
                intensity += 0.05       # autos feel every hit harder
            ts = event_time(rng, now_ms, d["stale"], city["utc_offset_h"])
            sid = sessions.get(city_key, vehicle, ts)
            events.append(make_event(rng, lat, lon, ts, vehicle, atype, intensity, sid))

    # Noise: isolated one-off triggers (speed breakers not yet confirmed, phone
    # knocks) scattered along corridors. DBSCAN should discard these.
    n_noise = int(len(events) * 0.08)
    lengths = {name: corridor_length(p) for name, p in city["corridors"].items()}
    for _ in range(n_noise):
        road = pick(rng, lengths)
        lat, lon = point_along(city["corridors"][road], rng.random() * lengths[road])
        if any(haversine_m(lat, lon, d["lat"], d["lon"]) < 70 for d in defects):
            continue
        vehicle = pick(rng, city["vehicle_mix"])
        ts = event_time(rng, now_ms, False, city["utc_offset_h"])
        sid = sessions.get(city_key, vehicle, ts)
        events.append(make_event(rng, lat, lon, ts, vehicle,
                                 rng.choice(["unknown", "rough_patch", "bump"]),
                                 rng.uniform(0.1, 0.4), sid))
    return events


# ---------------------------------------------------------------------------
# Port of backend/internal/clustering (DBSCAN + scoreCluster)
# ---------------------------------------------------------------------------

def dbscan(events, eps, min_pts):
    # Like the Go regionQuery, a point's neighbourhood excludes the point
    # itself, so a core point needs min_pts *other* events: with minPts=2 the
    # smallest cluster the backend can form has 3 events.
    # Grid index so region queries stay fast; results match brute force.
    cell = eps / 111_320.0
    grid = {}
    for i, e in enumerate(events):
        grid.setdefault((int(e["latitude"] / cell), int(e["longitude"] / cell)), []).append(i)

    def region(i):
        e = events[i]
        cx, cy = int(e["latitude"] / cell), int(e["longitude"] / cell)
        out = []
        for dx in (-2, -1, 0, 1, 2):
            for dy in (-2, -1, 0, 1, 2):
                for j in grid.get((cx + dx, cy + dy), ()):
                    if j != i and haversine_m(e["latitude"], e["longitude"],
                                   events[j]["latitude"], events[j]["longitude"]) <= eps:
                        out.append(j)
        return out

    visited = [False] * len(events)
    assigned = [False] * len(events)
    clusters = []
    for i in range(len(events)):
        if visited[i]:
            continue
        visited[i] = True
        nb = region(i)
        if len(nb) < min_pts:
            continue
        cluster = [i]
        assigned[i] = True
        queue = list(nb)
        k = 0
        while k < len(queue):
            j = queue[k]
            k += 1
            if not visited[j]:
                visited[j] = True
                m = region(j)
                if len(m) >= min_pts:
                    queue.extend(m)
            if not assigned[j]:
                assigned[j] = True
                cluster.append(j)
        clusters.append([events[x] for x in cluster])
    return clusters


def score_cluster(evs, now_ms, rng):
    n = len(evs)
    total_w = sum(VEHICLE_WEIGHT.get(e["vehicle_type"], 0.85) for e in evs)
    avg_int = sum(e["intensity"] * VEHICLE_WEIGHT.get(e["vehicle_type"], 0.85) for e in evs) / total_w
    lat = sum(e["latitude"] for e in evs) / n
    lon = sum(e["longitude"] for e in evs) / n
    types, vts = {}, {}
    for e in evs:
        types[e["anomaly_type"]] = types.get(e["anomaly_type"], 0) + 1
        vts[e["vehicle_type"]] = vts.get(e["vehicle_type"], 0) + 1
    dom, dom_n = max(types.items(), key=lambda kv: kv[1])
    last = max(e["timestamp_ms"] for e in evs)
    radius = max(haversine_m(lat, lon, e["latitude"], e["longitude"]) for e in evs)

    count_score = math.log10(total_w + 1) / math.log10(21)
    consistency = dom_n / n
    age = now_ms - last
    recency = max(0.0, 1.0 - age / MAX_AGE_MS) if age > 0 else 1.0
    diversity = len(vts)
    bonus = 0.08 * (diversity - 1)
    penalty = 0.08 if (diversity == 1 and "three_wheeler" in vts and n < 4) else 0.0
    conf = min(1.0, max(0.0, count_score * 0.50 + consistency * 0.25 + recency * 0.15 + bonus - penalty))
    return {
        "cluster_id": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
        "latitude": round(lat, 7), "longitude": round(lon, 7),
        "anomaly_type": dom, "confidence": round(conf, 4), "event_count": n,
        "avg_intensity": round(avg_int, 4), "last_seen_ms": last, "radius_m": round(radius, 2),
        "vehicle_type_counts": vts, "vehicle_type_diversity": diversity,
    }


# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------

def write_json(path, obj, compact=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        if compact:
            json.dump(obj, f, separators=(",", ":"))
        else:
            json.dump(obj, f, indent=1)
        f.write("\n")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--now", help="reference time, ISO 8601 (default: now)")
    args = ap.parse_args()

    rng = random.Random(args.seed)
    now = datetime.fromisoformat(args.now) if args.now else datetime.now(timezone.utc)
    now_ms = int(now.timestamp() * 1000)
    sessions = Sessions(rng)

    batch_dir = os.path.join(ROOT, "data", "synthetic", "batches")
    demo_dir = os.path.join(ROOT, "docs", "demo", "data")
    os.makedirs(batch_dir, exist_ok=True)
    for f in os.listdir(batch_dir):
        if f.endswith(".json"):
            os.remove(os.path.join(batch_dir, f))

    all_events, all_clusters, city_meta = [], [], {}
    for key, city in CITIES.items():
        defects = make_defects(rng, key, city)
        events = make_events(rng, defects, key, city, now_ms, sessions)
        events.sort(key=lambda e: e["timestamp_ms"])
        clusters = [score_cluster(c, now_ms, rng) for c in dbscan(events, CLUSTER_RADIUS_M, MIN_EVENTS)]
        for c in clusters:
            c["city"] = key
        clustered = sum(c["event_count"] for c in clusters)

        for i in range(0, len(events), 500):
            chunk = events[i:i + 500]
            write_json(os.path.join(batch_dir, f"{key}-{i // 500 + 1:02d}.json"), {
                "batch_id": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
                "submitted_at_ms": chunk[-1]["timestamp_ms"] + rng.randint(60_000, 900_000),
                "events": chunk,
            }, compact=True)

        city_meta[key] = {"name": city["name"], "center": city["center"], "zoom": city["zoom"],
                          "events": len(events), "clusters": len(clusters),
                          "noise_events": len(events) - clustered}
        all_events += [dict(e, city=key) for e in events]
        all_clusters += clusters
        print(f"{city['name']:>10}: {len(defects)} defects, {len(events)} events, "
              f"{len(clusters)} clusters, {len(events) - clustered} noise")

    # Same shape as GET /v1/stats (model.DashboardStats), plus demo extras.
    stats = {
        "total_events": len(all_events),
        "total_clusters": len(all_clusters),
        "events_24h": sum(1 for e in all_events if now_ms - e["timestamp_ms"] <= DAY_MS),
        "avg_confidence": round(sum(c["confidence"] for c in all_clusters) / len(all_clusters), 4),
        "generated_at_ms": now_ms,
        "seed": args.seed,
        "cities": city_meta,
    }
    # Lightweight event rows for the map's raw-events layer and timeline chart:
    # [lat, lon, timestamp_ms, vehicle_type index, anomaly_type index, city index]
    vt_idx = ["two_wheeler", "three_wheeler", "four_wheeler"]
    at_idx = ["pothole", "bump", "rough_patch", "unknown"]
    city_idx = list(CITIES)
    lite = {"fields": ["lat", "lon", "ts", "vehicle", "type", "city"],
            "vehicle_types": vt_idx, "anomaly_types": at_idx, "cities": city_idx,
            "rows": [[round(e["latitude"], 5), round(e["longitude"], 5), e["timestamp_ms"],
                      vt_idx.index(e["vehicle_type"]), at_idx.index(e["anomaly_type"]),
                      city_idx.index(e["city"])] for e in all_events]}

    write_json(os.path.join(demo_dir, "clusters.json"), {"clusters": all_clusters}, compact=True)
    write_json(os.path.join(demo_dir, "stats.json"), stats)
    write_json(os.path.join(demo_dir, "events-lite.json"), lite, compact=True)
    print(f"{'total':>10}: {len(all_events)} events, {len(all_clusters)} clusters, "
          f"avg confidence {stats['avg_confidence']:.2f}")
    if len(all_events) > 5000:
        print("warning: more than 5000 events; the backend clusters at most 5000 per run, "
              "so load.py should be run city by city with a clustering run in between")


if __name__ == "__main__":
    main()
