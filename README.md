# Asphalt

Asphalt detects road anomalies (potholes, speed bumps, rough patches) on US
roads using smartphone sensor data and aggregates them into a queryable road
quality map.

It is a privacy-first, offline-first, battery-aware system designed for real
deployment conditions: noisy sensors, intermittent connectivity, and heterogeneous
device hardware. Potential users include city public works and DOT road crews,
delivery and rideshare fleets, and navigation or insurance products that want
road-condition data without dashcams.

---

## How It Works

A driver installs an app that includes the Asphalt SDK. The app declares the
vehicle type (car or motorcycle) at initialisation time. While driving above
~9 mph (15 km/h), the SDK samples `TYPE_LINEAR_ACCELERATION` and
`TYPE_GRAVITY` at ~50Hz. The linear acceleration is projected onto the gravity
unit vector to extract the road-normal force component — this works regardless
of how the phone is mounted (flat, upright, angled in a holder). When the
road-normal reading deviates sharply from the rolling baseline (the pothole
spike-dip signature), and the gyroscope confirms actual physical motion, the SDK
records the event with a GPS coordinate and intensity score.

Events are batched locally and uploaded to the backend when connectivity is
available. The backend clusters nearby events, scores cluster confidence based
on how many independent reports agree, normalises signal strength across vehicle
types, and exposes the results via a map query API.

No raw sensor data leaves the device. No user accounts or identifiers are
collected. Location data is attached only to detected anomaly events, not
tracked continuously.

See [docs/sensor-model.md](docs/sensor-model.md) and
[docs/vehicle-profiles.md](docs/vehicle-profiles.md) for detailed explanations.

---

## Project Structure

```
asphalt/
  sdk/android/asphalt-sdk/     Android SDK (Kotlin)
  backend/                     Backend server (Go)
  demo-app/android/            Demo Android application
  contracts/                   JSON schemas and OpenAPI spec
  docs/                        Architecture, sensor model, setup guides
  docs/demo/                   Static demo map with synthetic data (GitHub Pages)
  tools/synthetic/             Synthetic data generator and loader
  data/synthetic/              Generated synthetic event batches
```

---

## Architecture

```mermaid
flowchart LR
    A[Linear Accel + Gravity\n~50Hz, orientation-agnostic] --> D[Spike-Dip Detector]
    B[Gyroscope ~50Hz] --> D
    C[GPS 1Hz] --> G[Speed Gate\n>9 mph]
    G --> D
    D --> E[Local SQLite Buffer]
    E --> F[WorkManager Upload]
    F -->|HTTPS JSON batch| H[Ingestion API\nGo]
    H --> I[(PostgreSQL)]
    I --> J[DBSCAN Clustering\nevery 5 min]
    J --> K[Map Query API]
    K --> L[Map Client]
```

Full architecture with component breakdown: [docs/architecture.md](docs/architecture.md)

---

## Quick Start

### Backend

```bash
cd backend
docker compose up --build
# API available at http://localhost:8080
```

Test the health endpoint:
```bash
curl http://localhost:8080/v1/health
```

Submit a test event batch:
```bash
curl -X POST http://localhost:8080/v1/ingest/batch \
  -H "Content-Type: application/json" \
  -d @contracts/example-batch.json
```

Full backend documentation: [docs/backend-setup.md](docs/backend-setup.md)

### Demo with synthetic data

A synthetic dataset (about 4,400 phone reports across New York City, Chicago
and Los Angeles) shows what a populated map looks like. View it without a backend by
opening the static demo map in `docs/demo/`, or load it into a running backend
so the live dashboard fills up:

```bash
python3 tools/synthetic/load.py --sql | psql "postgres://asphalt:asphalt@localhost:5432/asphalt"
# then open http://localhost:8080/dashboard.html
```

See [tools/synthetic/README.md](tools/synthetic/README.md) for details.

### Android SDK

```kotlin
// In Application.onCreate()
Asphalt.init(this, AsphaltConfig(
    ingestUrl = "https://your-backend.example.com/v1/ingest/batch",
    vehicleType = VehicleType.FOUR_WHEELER  // or TWO_WHEELER for motorcycles
))

// Start detection (call after location permission is granted)
Asphalt.start()

// Stop detection
Asphalt.stop()
```

Full SDK documentation: [docs/sdk-integration.md](docs/sdk-integration.md)

Vehicle profile documentation: [docs/vehicle-profiles.md](docs/vehicle-profiles.md)

### Demo APK (real-device testing)

The demo app at `demo-app/android/` is a self-contained APK you can install on
a physical Android device to test detection end-to-end.

**Prerequisites**

- Android Studio Hedgehog (2023.1) or later, or Android SDK command-line tools
- A physical Android device running Android 7.0+ (API 24) with Google Play
  Services installed (standard on all non-China devices)
- USB debugging enabled on the device
- The backend running and reachable from the device (see Backend section above)

> **No `google-services.json` needed.** The app only uses the Fused Location
> Provider (`play-services-location`), which is part of Google Play Services
> already installed on the device. Firebase and the `google-services` Gradle
> plugin are not used and do not need to be configured.

**Steps**

1. Create `demo-app/android/local.properties` pointing at your Android SDK:
   ```
   sdk.dir=/path/to/your/Android/sdk
   ```
   On macOS this is typically `~/Library/Android/sdk`.
   On Linux, `~/Android/Sdk`. On Windows, `C:\Users\<you>\AppData\Local\Android\Sdk`.

2. Set the backend URL for your device.

   If testing on an **emulator**, the default `http://10.0.2.2:8080` (emulator
   alias for host localhost) already works.

   If testing on a **physical device** over Wi-Fi, edit the `debug` block in
   `demo-app/android/app/build.gradle.kts` and replace `10.0.2.2` with your
   development machine's local IP address:
   ```kotlin
   buildConfigField("String", "INGEST_URL",
       "\"http://192.168.1.42:8080/v1/ingest/batch\"")
   ```

3. Build and install the debug APK:
   ```bash
   cd demo-app/android
   ./gradlew installDebug
   ```

4. Open the app, grant location permission when prompted, then press **Start**.

**Debug vs release behaviour**

| Behaviour | Debug build | Release build |
|-----------|-------------|---------------|
| Speed gate | ~3 mph (5 km/h) | ~9 mph (15 km/h) |
| Logging | Verbose (Logcat tag: `Asphalt`) | Silent |
| Backend URL | `http://10.0.2.2:8080` | Set in `build.gradle.kts` |
| Obfuscation | Off | R8 + ProGuard |

The lower speed gate in debug builds means you can trigger real detections while
walking slowly, without needing to drive.

---

## Sensor Model

The accelerometer detects vertical displacement caused by road anomalies.
A pothole creates a characteristic dip-then-spike signature on the road-normal
axis over 150-400ms; a speed bump produces the reverse (spike, then dip).
The gyroscope confirms physical angular motion, filtering out sensor noise
from vibration, car stereo bass, and loose phone mounts. GPS speed gates the
system to activate only during vehicle travel above ~9 mph (15 km/h).

See [docs/sensor-model.md](docs/sensor-model.md) for sample signal patterns
and full explanation.

---

## Vehicle Support

Asphalt models two vehicle categories, matching the US fleet:

| Vehicle | Examples | Detection Threshold | Gyro Confirmation | Signal Weight |
|---------|---------|---------------------|-------------------|---------------|
| Four-wheeler | Cars, SUVs, pickups, rideshare | 4.0 m/s^2 | 0.30 rad/s | 1.0 |
| Two-wheeler | Motorcycles, scooters, delivery mopeds | 5.0 m/s^2 | 0.45 rad/s | 0.8 |

Motorcycles are noisier sensors than cars: the rider leans into corners, the
handlebars pass engine and road buzz straight to a mounted phone, and hard
braking pitches the bike forward. The two-wheeler profile compensates with a
higher detection threshold, a stricter gyro check and a wider rolling-baseline
window.

On the backend:

- **Signal weights**: Motorcycle events count 0.8x relative to cars when
  computing cluster intensity and confidence.
- **Cross-vehicle confidence bonus**: A cluster confirmed by both cars and
  motorcycles receives +0.08 confidence. Two vehicle types with different
  suspensions reporting the same spot is the strongest evidence a defect is real.

See [docs/vehicle-profiles.md](docs/vehicle-profiles.md) for the full
explanation and worked confidence examples.

### What makes US roads hard

- **Freeze-thaw damage**: In northern states, potholes appear and grow quickly
  through late winter and spring. Recency decay in confidence scoring lets old
  reports fade as roads are patched.
- **Speed humps, rail crossings, steel plates**: Traffic calming, grade
  crossings, utility-work plates and manhole covers all produce sharp impacts.
  Some are hazards worth reporting; many are permanent infrastructure.
- **Freeway speeds**: At 65+ mph the same defect produces a larger G-force, so
  intensity is speed-normalised.
- **Urban canyons**: GPS in downtown Manhattan, Chicago's Loop or under elevated
  highways can degrade to 30-50m accuracy, above the 30m cluster radius.
- **Rush-hour congestion**: Stop-and-go traffic below ~9 mph is not surveyed,
  so damage at bottlenecks is underreported.

---

## Design Principles

**Battery aware**: Sensors are inactive below ~9 mph (15 km/h). Uploads use WorkManager
to batch events and avoid unnecessary radio wake-ups.

**Privacy first**: No PII collected. No device identifiers, user accounts, or
continuous location tracking. Location is recorded only when an anomaly is
detected.

**Noise tolerant**: Three-sensor fusion (accelerometer + gyroscope + GPS) with
a rolling baseline and configurable thresholds. No single sensor is trusted alone.

**Offline first**: Events are written to local SQLite before any network
operation. Upload failures are retried by WorkManager with exponential backoff.

**Modular**: The SDK, backend, and contracts are independent. The SDK can be
used with a custom backend. The backend can accept events from non-Android sources.

---

## Backend Choice: Go

Go was chosen over Node.js for the backend:
- Goroutine concurrency handles many simultaneous batch uploads with low memory
- Predictable GC latency avoids client timeout-caused retries
- Single static binary, minimal deployment footprint
- Strong standard library covers HTTP, JSON, and SQL without heavy dependencies

---

## Known Limitations

- **Unvalidated thresholds**: Detection thresholds were set from published
  vehicle-dynamics ranges, not from labelled US field data. A ground-truth
  drive study is the most important next step.
- **No per-device calibration**: Thresholds are per vehicle type, not per
  phone model. Sensor gain and OEM filtering vary between devices.
- **No motorcycle turn filter**: Cornering lean is handled only by the raised
  threshold; a dedicated lean/turn filter is planned.
- **Sparse coverage**: Rural roads and quiet streets will have low confidence
  scores until more drivers report the same location.
- **GPS spoofing**: Not prevented in v1; multi-report confidence scoring
  limits but does not eliminate its impact.
- **Android only**: There is no iOS SDK yet, and more than half of US
  smartphone users are on iPhone.
- **Background collection**: Requires a Foreground Service with
  `foregroundServiceType="location"` on Android 10+. The demo app includes
  `DetectionForegroundService`, which handles this when the user taps Start.

Phone orientation is not a limitation: the SDK projects sensor readings onto
the gravity vector, so dashboard holders, windshield mounts and cupholders all
work.

Full limitations: [docs/limitations.md](docs/limitations.md)

---

## API Contract

The event schema, batch format, and HTTP API are defined in `contracts/`:

- `contracts/event.schema.json` - single event JSON schema
- `contracts/batch.schema.json` - batch upload JSON schema
- `contracts/api.yaml` - OpenAPI 3.1 specification

---

## License

Apache 2.0. See [LICENSE](LICENSE).
