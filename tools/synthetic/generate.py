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

Road corridors are hand-traced waypoints along real arterial roads in
Bengaluru, Delhi NCR and Mumbai. They are approximate: a point can sit a few
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
IST_OFFSET_MS = 330 * 60 * 1000
SDK_VERSION = "1.0.0"

# ---------------------------------------------------------------------------
# Cities and road corridors (lat, lon waypoints along real roads)
# ---------------------------------------------------------------------------

CITIES = {
    "bengaluru": {
        "name": "Bengaluru",
        "center": [12.9600, 77.6200],
        "zoom": 12,
        "defects": 200,
        # Share of reports by vehicle type: autos and bikes dominate Indian traffic.
        "vehicle_mix": {"two_wheeler": 0.45, "three_wheeler": 0.30, "four_wheeler": 0.25},
        "corridors": {
            "Outer Ring Road": [(12.9172, 77.6229), (12.9237, 77.6480), (12.9260, 77.6760),
                                (12.9569, 77.7011), (12.9760, 77.6960), (13.0070, 77.6960),
                                (13.0230, 77.6450), (13.0400, 77.6230), (13.0358, 77.5970)],
            "Hosur Road": [(12.9172, 77.6229), (12.9000, 77.6310), (12.8840, 77.6450), (12.8450, 77.6600)],
            "Sarjapur Road": [(12.9237, 77.6480), (12.9120, 77.6800), (12.9010, 77.6960), (12.8870, 77.7300)],
            "Bannerghatta Road": [(12.9380, 77.6010), (12.9100, 77.6000), (12.8900, 77.5970), (12.8700, 77.5960)],
            "Old Airport Road": [(12.9600, 77.6400), (12.9570, 77.6600), (12.9560, 77.7000)],
            "Whitefield Main Road": [(12.9569, 77.7011), (12.9700, 77.7250), (12.9850, 77.7450)],
            "Tumkur Road": [(13.0100, 77.5550), (13.0300, 77.5300), (13.0450, 77.5100)],
            "Mysore Road": [(12.9600, 77.5600), (12.9450, 77.5300), (12.9250, 77.4950)],
            "Bellary Road": [(13.0358, 77.5970), (13.0700, 77.5950), (13.1000, 77.5960)],
            "100 Feet Road Indiranagar": [(12.9610, 77.6410), (12.9720, 77.6410), (12.9790, 77.6400)],
        },
        # Stretches with notoriously bad surfaces get extra, stronger defects.
        "hotspots": ["Outer Ring Road", "Sarjapur Road", "Hosur Road"],
    },
    "delhi": {
        "name": "Delhi NCR",
        "center": [28.5800, 77.1900],
        "zoom": 11,
        "defects": 175,
        "vehicle_mix": {"two_wheeler": 0.40, "three_wheeler": 0.25, "four_wheeler": 0.35},
        "corridors": {
            "Ring Road": [(28.5680, 77.2090), (28.5920, 77.1620), (28.6300, 77.1400), (28.6720, 77.1300),
                          (28.7080, 77.1800), (28.6680, 77.2280), (28.6300, 77.2500), (28.5720, 77.2600),
                          (28.5650, 77.2350), (28.5680, 77.2090)],
            "Mathura Road": [(28.6100, 77.2400), (28.5720, 77.2600), (28.5200, 77.2900), (28.4900, 77.3050)],
            "NH-48 Delhi-Gurugram": [(28.5920, 77.1620), (28.5500, 77.1200), (28.5050, 77.0900),
                                     (28.4800, 77.0700), (28.4600, 77.0400)],
            "Golf Course Road": [(28.4800, 77.1000), (28.4500, 77.1000), (28.4200, 77.1000)],
            "Mehrauli-Gurugram Road": [(28.4800, 77.0800), (28.4960, 77.1500), (28.5200, 77.1850)],
            "Noida Expressway": [(28.5700, 77.3200), (28.5350, 77.3500), (28.4900, 77.4000), (28.4600, 77.4700)],
        },
        "hotspots": ["Mehrauli-Gurugram Road", "Ring Road"],
    },
    "mumbai": {
        "name": "Mumbai",
        "center": [19.1100, 72.8900],
        "zoom": 12,
        "defects": 165,
        "vehicle_mix": {"two_wheeler": 0.35, "three_wheeler": 0.35, "four_wheeler": 0.30},
        "corridors": {
            "Western Express Highway": [(19.0600, 72.8450), (19.1150, 72.8550), (19.1650, 72.8600),
                                        (19.2300, 72.8650), (19.2550, 72.8700)],
            "Eastern Express Highway": [(19.0400, 72.8650), (19.0850, 72.9150), (19.1100, 72.9300),
                                        (19.1700, 72.9550), (19.2000, 72.9750)],
            "LBS Marg": [(19.0700, 72.8900), (19.0900, 72.9050), (19.1300, 72.9300), (19.1750, 72.9450)],
            "JVLR": [(19.1350, 72.8500), (19.1300, 72.8800), (19.1250, 72.9150)],
            "SV Road": [(19.0550, 72.8350), (19.1150, 72.8450), (19.1650, 72.8480)],
            "Andheri-Kurla Road": [(19.1150, 72.8550), (19.0950, 72.8800), (19.0700, 72.8850)],
            "Sion-Panvel Highway": [(19.0400, 72.8650), (19.0500, 72.9200), (19.0350, 73.0200)],
        },
        "hotspots": ["JVLR", "Andheri-Kurla Road", "Sion-Panvel Highway"],
    },
}

# ---------------------------------------------------------------------------
# Sensor model, mirroring sdk/.../detection/VehicleProfile.kt thresholds
# ---------------------------------------------------------------------------

PROFILES = {
    #                 z-delta threshold, gyro threshold, typical speed range (km/h)
    "four_wheeler":  {"delta": 4.0, "gyro": 0.30, "speed": (15, 60)},
    "two_wheeler":   {"delta": 5.0, "gyro": 0.45, "speed": (15, 45)},
    "three_wheeler": {"delta": 5.5, "gyro": 0.55, "speed": (12, 35)},
}

VEHICLE_WEIGHT = {"four_wheeler": 1.0, "two_wheeler": 0.8, "three_wheeler": 0.7}

INTENSITY = {  # base intensity range per true defect type
    "pothole": (0.50, 0.95),
    "bump": (0.35, 0.70),
    "rough_patch": (0.20, 0.45),
}

# Indian-market devices: (manufacturer, model, sensor vendor, typical sdk_int)
DEVICES = [
    ("Xiaomi", "Redmi Note 12", "Bosch", 33), ("Xiaomi", "Redmi 12 5G", "Bosch", 33),
    ("Xiaomi", "Redmi Note 10", "Bosch", 31), ("samsung", "SM-A146B", "STMicro", 34),
    ("samsung", "SM-M146B", "STMicro", 33), ("samsung", "SM-A225F", "STMicro", 31),
    ("realme", "RMX3491", "mCube", 33), ("realme", "RMX3710", "Bosch", 34),
    ("vivo", "V2207", "Bosch", 33), ("vivo", "V2135", "mCube", 31),
    ("OPPO", "CPH2477", "Bosch", 33), ("OnePlus", "CPH2465", "Bosch", 34),
    ("motorola", "moto g54 5G", "Bosch", 34), ("POCO", "M2102J20SI", "Bosch", 30),
]


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
    x = rng.random() * sum(weights.values())
    for k, w in weights.items():
        x -= w
        if x <= 0:
            return k
    return k


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
        tier = pick(rng, {"weak": 0.22, "auto_only": 0.06, "mid": 0.52,
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
    if defect["tier"] == "showcase":            # guaranteed all three types
        vs[:3] = ["two_wheeler", "three_wheeler", "four_wheeler"]
    return vs


def event_time(rng, now_ms, stale):
    """Timestamp in IST commute peaks, recent-weighted, within 90 days."""
    if stale:
        days_ago = rng.uniform(55, 88)
    else:
        days_ago = rng.expovariate(1 / 28.0)
        if days_ago > 88:
            days_ago = rng.uniform(0, 88)
    day_start = now_ms - int(days_ago) * DAY_MS
    day_start -= (day_start + IST_OFFSET_MS) % DAY_MS           # midnight IST
    hour = pick(rng, {"am": 0.42, "pm": 0.43, "mid": 0.15})
    h = {"am": rng.uniform(8, 11), "pm": rng.uniform(17, 21), "mid": rng.uniform(11, 17)}[hour]
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


def make_event(rng, lat, lon, ts, vehicle, atype, intensity, device, session_id):
    lo, hi = PROFILES[vehicle]["speed"]
    manufacturer, model_name, vendor, sdk_int = device
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
        "device_meta": {
            "platform": "android", "sdk_int": sdk_int, "manufacturer": manufacturer,
            "model": model_name, "sensor_vendor": vendor,
        },
        "sdk_version": SDK_VERSION,
        "session_id": session_id,
    }


class Sessions:
    """Groups events into anonymous drive sessions: one device, one vehicle
    type, one day, 3-40 events."""

    def __init__(self, rng):
        self.rng = rng
        self.open = {}

    def get(self, city, vehicle, ts):
        key = (city, vehicle, ts // DAY_MS, self.rng.randint(0, 5))
        s = self.open.get(key)
        if s is None or s["left"] <= 0:
            s = {"id": str(uuid.UUID(int=self.rng.getrandbits(128), version=4)),
                 "device": self.rng.choice(DEVICES), "left": self.rng.randint(3, 40)}
            self.open[key] = s
        s["left"] -= 1
        return s["id"], s["device"]


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
            ts = event_time(rng, now_ms, d["stale"])
            sid, device = sessions.get(city_key, vehicle, ts)
            events.append(make_event(rng, lat, lon, ts, vehicle, atype, intensity, device, sid))

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
        ts = event_time(rng, now_ms, False)
        sid, device = sessions.get(city_key, vehicle, ts)
        events.append(make_event(rng, lat, lon, ts, vehicle,
                                 rng.choice(["unknown", "rough_patch", "bump"]),
                                 rng.uniform(0.1, 0.4), device, sid))
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
