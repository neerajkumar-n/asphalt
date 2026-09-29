# Synthetic demo data

Everything here is synthetic. It exists so people can see what Asphalt looks
like once phones have been reporting for a while, before real data exists.

| Path | What it is |
|---|---|
| `tools/synthetic/generate.py` | Builds the dataset (seeded, reproducible, standard library only) |
| `tools/synthetic/load.py` | Loads it into a running backend |
| `data/synthetic/batches/*.json` | `EventBatch` payloads, valid against `contracts/batch.schema.json` |
| `docs/demo/index.html` | Static map that runs on GitHub Pages without the backend |
| `docs/demo/data/*.json` | Pre-computed clusters, stats and events for that map |

## What the data looks like

- **Cities:** New York City, Chicago and Los Angeles. Defects sit along real
  arterial roads and freeways (Broadway, the BQE, Grand Concourse, Lake Shore
  Drive, the Dan Ryan, Western Avenue, I-405, I-10, Crenshaw Boulevard and
  others), traced by hand, so a point can be a few tens of metres off the
  carriageway. Roads known for bad surfaces get more and stronger defects.
- **Events:** about 4,400 phone reports over the last 90 days, weighted to
  recent weeks and to local commute peaks (7 to 9:30 am and 4 to 7 pm in each
  city's time zone). Each one follows `contracts/event.schema.json`: GPS
  scatter of a few metres around the true defect, US urban and freeway speeds,
  sensor values that clear the thresholds in `VehicleProfile.kt` for that
  vehicle, and one anonymous session per drive. Device metadata carries only
  the two required fields (`platform` and `sdk_int`); phone make, model and
  sensor vendor are left out.
- **Vehicle mix:** mostly cars, with two-wheelers (motorcycles and delivery
  mopeds) making up 8 to 15% depending on the city. There are no
  three-wheelers, as they are effectively absent from US roads, so
  cross-vehicle confirmation is between cars and two-wheelers.
- **Confidence spread:** weak single-vehicle defects (3 to 4 reports), typical
  mixed defects (4 to 9), strong ones (10 to 16) and showcase craters (20 to 32)
  confirmed by every vehicle type the city has. A city configured with
  three-wheelers also gets auto-only defects that trigger the three-wheeler
  penalty. About 12% of defects have not been reported for two months, so
  recency decay shows. About 8% of events are isolated noise that clustering
  discards.
- **Disagreement:** about 14% of reports classify the defect differently (a bump
  read as a pothole), which lowers type consistency the way real data would.

Clusters for the static map are computed with a Python port of
`backend/internal/clustering` (DBSCAN, eps 30 m, the same confidence formula).
Loading the batches into the real backend produces the same clusters and
confidence values.

## View it

**Without a backend (GitHub Pages).** In the repository settings, under Pages,
deploy from the `main` branch and the `/docs` folder. The map is then at
`https://<user>.github.io/asphalt/demo/`. Locally:

```bash
cd docs/demo && python3 -m http.server 8000   # open http://localhost:8000
```

**In the real dashboard.** Start the backend (`cd backend && docker compose up`)
so the schema exists, then load the data one of two ways:

```bash
# Keeps the full 90-day history. Recommended for demos.
python3 tools/synthetic/load.py --sql | psql "postgres://asphalt:asphalt@localhost:5432/asphalt"

# Through the real ingestion API. The API rejects events older than 7 days,
# so this squeezes the timeline into the last week.
python3 tools/synthetic/load.py --url http://localhost:8080
```

Clusters appear after the next clustering run (`CLUSTER_INTERVAL`, 5 minutes by
default; set it to `30s` for demos). Then open
`http://localhost:8080/dashboard.html` and zoom into a city.

The clustering job picks up at most 5000 unclustered events per run and gives
each run new cluster IDs, so load the whole set in one go. The default dataset
stays under 5000 events for this reason.

## Regenerate

```bash
python3 tools/synthetic/generate.py              # seed 42, timestamps relative to now
python3 tools/synthetic/generate.py --seed 7     # a different, reproducible dataset
```

City definitions, road corridors, defect counts and vehicle mixes are at the top
of `generate.py`.
