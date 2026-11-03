# Field test kit

Measures how well Asphalt detects real road defects. You label the defects on
a short route, drive it several times with the demo app, and score the
detections against your labels.

It answers three questions:

1. **Recall per pass**: of the defects your tires went over, how many did one
   drive detect?
2. **Precision**: of the detections, how many were real defects?
3. **Map quality**: after all passes, do the clusters a customer would see sit
   on real defects, and does every defect get one?

| File | What it is |
|---|---|
| `templates/ground_truth.csv` | Answer key: every defect and road feature on the route |
| `templates/runs.csv` | Drive log: one row per pass |
| `export_events.sql` | Exports detections from the backend as CSV |
| `score.py` | Scores detections against the answer key; writes `report.json` and `map.html` |
| `example/` | A synthetic test (5 passes on a Chicago street) to try the scorer on |
| `test_score.py` | Tests for the scorer |

Standard library Python 3.9+ only. Try it now on the example:

```bash
python3 tools/fieldtest/score.py \
  --truth tools/fieldtest/example/ground_truth.csv \
  --runs tools/fieldtest/example/runs.csv \
  --detections tools/fieldtest/example/detections.csv \
  --out fieldtest-results
open fieldtest-results/map.html
```

---

## 1. Plan the route

- **2 to 5 miles** of city street you can loop or repeat, driven in the same
  lane and direction every pass.
- **At least 10 defects in your wheel path**, ideally 15 to 20. With 12
  defects and 5 passes you have 60 chances to detect, which pins recall down
  to about ±12 points. More defects tighten that.
- A mix: mostly potholes, a few speed humps, a rough patch, and some
  **features** that are not defects but jolt the car: manhole covers, steel
  plates, rail crossings, expansion joints. These tell you how often the
  system mistakes infrastructure for damage.
- Normal speeds (20 to 35 mph) and light traffic, so you can hold your lane.

## 2. Build the answer key

Copy `templates/ground_truth.csv` and fill one row per point. Survey on foot
or with a passenger driving slowly, **before** the test drives.

| Column | How to fill it |
|---|---|
| `id` | Short label you will recognise, e.g. `P07`, `B02`, `F03` |
| `lat`, `lon` | In Google Maps, switch to satellite view, long-press the exact spot, copy the coordinates. Aim for 3 m accuracy. |
| `type` | `pothole`, `bump`, `rough_patch` or `feature` |
| `expect_hit` | `yes` only if your tires will go over it on every pass. Use `no` for defects in another lane or at the curb: a detection there is still correct, but missing it does not count against recall. Features are never expected. |
| `severity` | Optional, 1 (minor) to 3 (severe). Lets you see whether misses are mostly small defects. |
| `notes` | Lane, wheel, what it looks like. A photo of each is worth taking. |

Rows starting with `#` are ignored.

## 3. Set up the phone and backend

You do not need a hosted backend. The SDK stores events on the phone and
uploads them when it can reach the server, so you can drive on mobile data
and let the phone upload when it is back on your home Wi-Fi.

1. Start the backend on your computer: `cd backend && docker compose up --build`.
2. Find your computer's Wi-Fi address (macOS: `ipconfig getifaddr en0`).
3. Build and install the demo app pointing at it, with the phone on the same
   Wi-Fi:
   ```bash
   cd demo-app/android
   ./gradlew installDebug -PingestUrl="http://192.168.1.42:8080/v1/ingest/batch"
   ```
4. Open the app, accept the consent screen, grant location when prompted,
   pick the vehicle type, and press **Start**. Detection keeps running with
   the screen off.

Mount the phone the same way every pass. Change one thing at a time: if you
want to compare a dash holder with a cupholder, do separate passes for each
and record the mount in the drive log.

**Do not press Simulate bump during a test.** Simulated events land in the run
window at made-up coordinates and would be scored as false positives.

## 4. Drive the passes

- **At least 3 passes**, ideally 5 or more. The backend needs 3 detections
  within 30 m before a defect appears on the map, so fewer than 3 passes can
  never produce a cluster.
- A **passenger** records the start and end time of each pass in the drive
  log. The driver does not touch the phone.
- Leave a minute or two between passes so the time windows do not overlap.

Fill one `runs.csv` row per pass:

| Column | Example |
|---|---|
| `run_id` | `R1` |
| `start`, `end` | `2026-11-08T10:02:00-06:00` (include the UTC offset; times without one are read as your computer's local time) |
| `vehicle_type` | `four_wheeler` or `two_wheeler` |
| `phone`, `mount` | `Pixel 8`, `dash holder` |
| `distance_mi` | Optional. Enables false positives per mile. |

## 5. Upload and export

Back on home Wi-Fi, open the app and leave it running. Uploads run on
Android's WorkManager schedule, so allow up to 30 minutes. Check that events
arrived:

```bash
curl -s http://localhost:8080/v1/stats
```

Then export them from the repository root:

```bash
docker compose -f backend/docker-compose.yml exec -T db \
  psql -U asphalt asphalt -f - < tools/fieldtest/export_events.sql > detections.csv
```

## 6. Score

```bash
python3 tools/fieldtest/score.py --truth ground_truth.csv --runs runs.csv \
  --detections detections.csv --out fieldtest-results
```

| Option | Default | Meaning |
|---|---|---|
| `--radius` | 25 | Metres within which a detection counts as a labelled point |
| `--min-confidence` | 0.30 | Cluster confidence the map shows (the tile API default) |
| `--min-speed-mph` | 9 | Ignores detections below this speed. The debug app detects from ~3 mph; the release app from ~9 mph. `0` keeps everything. |

Open `fieldtest-results/map.html` to see every labelled point coloured by how
often it was caught, every detection, and every cluster.

## 7. Read the results

| Result | What it means | What to try |
|---|---|---|
| Low recall, high precision | The detector is too strict for this phone or mount | Check the most-missed list: if misses are minor (severity 1) this may be fine. Otherwise try a firmer mount, or lower `detectionThresholdMs2` in `VehicleProfile.kt` and retest. |
| Low precision | False triggers | Open the map: false positives clustered at one spot are usually an unlabelled feature or defect; add it to the answer key. Scattered ones point to a threshold that is too low. |
| Many detections at features | Infrastructure looks like damage | Expected today. Note which kinds; it sizes the map-integration work. |
| Median location error above ~10 m | GPS or labelling error | Check your labels on satellite view; urban canyons degrade GPS. |
| Type accuracy low | Pothole vs bump classification is unreliable | Customers may need "road defect" rather than a specific type. |
| Single-pass numbers weak, map numbers strong | Clustering is doing its job | This is the expected shape: repeated passes filter noise. |

The **threshold sweep** shows what would happen if the detection threshold were
raised by 0.5 to 2.0 m/s². It can only simulate raising it, because events
below the current threshold were never recorded. To explore lower thresholds,
build the SDK with a lower `detectionThresholdMs2` and drive again.

### Suggested bars for a first pilot

These are starting points for discussion, not industry standards:

| Metric | Bar |
|---|---|
| Map precision (5+ passes) | ≥ 90% — a city crew sent to a pothole that is not there loses trust fast |
| Map recall (5+ passes) | ≥ 70% of expected defects |
| Single-pass precision | ≥ 75% |
| False positives per mile | ≤ 1 |

## Running the tests

```bash
python3 tools/fieldtest/test_score.py
```
