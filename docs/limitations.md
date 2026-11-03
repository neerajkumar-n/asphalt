# Known Limitations

This document describes the real-world constraints and limitations of Asphalt
v1. Understanding these is important for evaluating the system's suitability for
a given use case and for planning future improvements.

---

## 1. Battery Consumption

**What happens**: Sensor collection at 50Hz draws 0.5-1.5mA per sensor
(accelerometer + gyroscope). GPS tracking at 1Hz via FusedLocationProvider
draws 15-50mA depending on satellite lock quality. Together, active collection
adds 5-10% battery drain per hour of driving compared to a phone sitting idle.

**Mitigations in v1**:
- Speed gate: sensors are inactive below 15 km/h (~9 mph)
- WorkManager batch uploads: radio wakes up infrequently, not per event
- No wake lock: relies on Android sensor hardware FIFO for delivery

**Remaining constraints**:
- No activity recognition integration in v1. The SDK cannot distinguish a car
  trip from a bus ride. A passenger on a bus will collect data unnecessarily.
  Android's Activity Recognition API (`ActivityRecognitionClient`) would let
  the SDK activate only when the device detects `IN_VEHICLE` activity.
- High-speed highway driving keeps sensors active continuously. On a 3-hour
  highway trip, this is measurable battery impact.

---

## 2. Sensor Noise

**What happens**: Accelerometers are noisy micro-electromechanical systems
(MEMS). Real-world noise sources include:
- Engine idle vibration: 10-80Hz oscillation, amplitude ~0.5-2 m/s^2
- Road surface texture (not potholes): continuous low-amplitude noise
- Wind buffeting at high speed: broadband vibration
- Car stereo bass: coincides with Z-axis at high volume

**Mitigations in v1**:
- Rolling median baseline instead of instantaneous comparison
- Gyroscope confirmation threshold: most vibration noise has near-zero angular
  momentum and fails the gyro check
- Cooldown between detected events (1500ms for both vehicle types). Reduces
  double-counting of a single anomaly.

**Remaining constraints**:
- The detection threshold (4.0 m/s^2 default) was set empirically. It is a
  single global value. Optimal thresholds vary by vehicle type (motorcycle vs.
  SUV vs. truck), phone mounting location, and road surface category.
- No frequency-domain analysis. A bandpass filter isolating the 2-10Hz band
  relevant to suspension response would improve SNR, but requires more
  computation and a longer buffer.
- Engine idle vibration at stoplights occasionally triggers false positives when
  the vehicle accelerates sharply from a standing start. The speed gate at 15
  km/h (~9 mph) catches most of these, but not all.

---

## 3. Device Variability

**What happens**: There are thousands of distinct Android devices with different
accelerometer sensors, mounting positions, and OEM software modifications. A
threshold calibrated on a Pixel 7 may be too sensitive or too insensitive on a
Samsung Galaxy A series or a Xiaomi device.

**Known sources of variance**:
- Sensor offset: different zero-G bias per device
- Sensor gain: different m/s^2 per count, leading to different apparent deltas
- OEM smoothing: some manufacturers apply hardware or software low-pass filters
  before delivering sensor events to the application layer, reducing peak values
- Sampling rate delivery: `SENSOR_DELAY_GAME` requests 50Hz but Android does
  not guarantee this rate; it is a hint. Actual delivery rate varies from 30-60Hz
  depending on the SoC and sensor driver

**Mitigations in v1**:
- `device_meta.manufacturer`, `device_meta.model`, and `device_meta.sensor_vendor`
  fields exist in the event schema and are designed for backend per-model analysis.

**DeviceMeta population**: `DeviceMeta.platform`, `.manufacturer`, `.model`,
and `.sdkInt` are populated automatically from `android.os.Build.*` for every
real detection. `DeviceMeta.sensor_vendor` is populated from the accelerometer's
`Sensor.vendor` string via `SensorCollector.getSensorVendor()`. All five fields
arrive correctly in production events and are available for per-model backend analysis.

**Remaining constraint**: v1 does not dynamically calibrate per device.
A future version should compute per-device baseline noise floor over the first
few minutes of each session and set the threshold as `noise_floor * K` rather
than a fixed 4.0 m/s^2.

---

## 4. Phone Orientation

**What happens**: Road anomaly detection requires measuring acceleration along
the road-normal (vertical) axis. If only the raw Z-axis of the device were used,
the phone would need to lie flat to work correctly. In a dashboard holder or
windshield mount, the device Z-axis is horizontal, and pothole signals would
land on X or Y instead.

**Mitigation implemented in v1**: The SDK uses `TYPE_LINEAR_ACCELERATION` (raw
accelerometer minus gravity, as computed by the OS sensor fusion) and
`TYPE_GRAVITY` (continuous gravity vector from the low-pass accelerometer). At
each sample, the linear acceleration vector is projected onto the gravity unit
vector via a dot product. This yields the road-normal component regardless of
how the phone is mounted — flat on a seat, upright in a holder, or angled in a
cupholder. See `SensorCollector.projectOntoGravity()`.

**Remaining constraint**: The gravity vector requires a stable reading to
initialise. In the first ~0.5–1 second after sensors register, the gravity
vector defaults to `[0, 0, 9.81]` (phone-flat assumption). Any event detected
in that brief window may carry a small orientation error. In practice, sensors
do not activate until speed exceeds the threshold (~1–2 seconds after the
vehicle starts moving), so this window is rarely a problem.

---

## 5. Data Sparsity

**What happens**: In early deployment or in regions with few users, many road
segments have zero or one observed event. The clustering confidence model
requires at least 2 independent reports before a cluster exceeds the default
0.3 confidence display threshold. This means real potholes may not appear on
the map until a second user drives over them.

**Impact**: Rural roads and less-travelled urban streets are underrepresented.
The map will reflect where people drive, not where roads are bad.

**Mitigations**:
- The confidence threshold is configurable via the query API
- Operators can lower `min_confidence` to show single-report clusters for
  high-priority use cases (e.g. emergency pothole reporting)

---

## 6. GPS Accuracy and Spoofing

**What happens**: GPS accuracy in urban canyons (tall buildings on both sides)
degrades to 20-50m or worse. This makes the reported event location imprecise.
At 50m accuracy, a pothole might be attributed to the wrong lane or even the
wrong street.

Additionally, fake GPS apps (often used for gaming) can spoof location data.
A malicious actor could deliberately submit fake events at arbitrary coordinates
to pollute the road quality map.

**Mitigations in v1**:
- Events with `accuracy_m > 50` are stored but the backend can use this field
  to down-weight low-accuracy events in clustering
- Clustering requires multiple independent reports (different `session_id`
  values from different times) to reach high confidence; a single bad actor
  submitting many events from one session has limited impact
- The `speed_kmh` field provides a basic sanity check (a GPS-spoofed stationary
  device submitting 50 mph speed data is detectable)

**Remaining gaps**:
- v1 has no IP-based or device-fingerprint-based rate limiting
- No anomaly detection on the ingestion side to identify sessions with
  statistically improbable event patterns (e.g. 50 potholes per kilometre)
- FLP can be fooled by mock location providers at the OS level

---

## 7. Intermittent Connectivity

**What happens**: Mobile data is not always available. Tunnels, rural areas,
and indoor parking structures all produce gaps. The SDK must not lose events
or corrupt data during connectivity gaps.

**Mitigations in v1**:
- All events are persisted to Room (SQLite) before any upload attempt
- WorkManager retries failed uploads with exponential backoff
- `maxBufferSize` prevents unbounded growth; if exceeded, an immediate upload
  is attempted and new events are still stored

**Remaining constraint**: If a device is offline for more than 7 days, the
backend rejects events as too old (timestamp validation). Events older than
24 hours in Room are pruned after upload. A device that is offline for more
than 7 days will lose those events permanently.

---

## 8. Backend Single Region

**v1 runs in a single geographic region.** This means:
- Higher latency for users far from the deployment region
- No disaster recovery if the deployment region has an outage

For a production global deployment, the backend should be deployed in multiple
regions with regional write endpoints and a global read layer. This is outside
the scope of v1.

---

## 9. Two-Wheeler Signal Complexity

**What happens**: Motorcycles, scooters and delivery mopeds produce a higher
noise floor than cars. The rider leans into corners, the handlebars transmit
engine and road buzz directly to a mounted phone, and the lighter suspension
passes more of every impact through. v1 handles this only with a raised
detection threshold (5.0 vs 4.0 m/s^2), a stricter gyro confirmation
(0.45 vs 0.3 rad/s) and a wider baseline window. There is no dedicated lean
or turn filter.

**Known residual false positives**:

- **Cornering lean**: A 20-30 degree lean on a corner shifts the gravity
  vector. The gravity projection tracks this, but rapid lean transitions
  (a quick left-right swerve) can briefly look like an impact.

- **Hard braking**: Aggressive braking pitches the bike forward, which can
  look like the spike phase of a pothole.

- **Pillion passengers and cargo**: A scooter with a passenger, or a delivery
  moped with a loaded top box, has different suspension compression and body
  dynamics. The same pothole produces a different Z delta.

- **Mount position**: A handlebar mount sees more vibration than a tank or
  pocket position. Thresholds are calibrated for a handlebar mount.

**Mitigations in v1**:
- Higher detection and gyro thresholds reduce false positives
- Backend signal weight (0.8) reduces the influence of motorcycle-only reports
- Cross-vehicle diversity bonus rewards car confirmation of motorcycle reports

**Planned improvements**:
- A motorcycle turn and lean filter that suppresses detection during sustained
  roll and yaw (cornering)
- Per-operator threshold calibration (delivery fleets could run a calibration
  ride on a known smooth road to measure their bikes' baseline noise floor)

---

## 10. Dense Urban Environment Noise

**What happens**: US city streets combine noise sources that look like
potholes to a motion sensor but are not road damage:

- **Speed humps and speed tables**: Residential traffic calming varies widely
  in height and profile, and most installations are not in any map database,
  so there is no way to pre-filter known humps. These are detected as genuine
  anomalies (BUMP), which is correct behaviour, but they can dominate the map
  in some neighbourhoods.

- **Railroad crossings**: Grade crossings create a distinctive double-impact
  signature (two rails, spaced ~1.4m apart). At 20 mph, the two impacts
  arrive ~160ms apart. The detector may report this as a single pothole event
  or two separate events depending on cooldown timing.

- **Steel plates, manhole covers and expansion joints**: Road plates over
  utility work, raised or sunken manhole covers, and bridge expansion joints
  all produce sharp impacts. Some are genuine hazards worth reporting; others
  are permanent infrastructure.

- **Speed humps in series**: Traffic calming installations often use 3-4 speed
  humps in quick succession (every 20-30m). At 20 mph, these arrive every
  ~2.2-3.4 seconds, outside the 1.5-second cooldown, so each hump is reported.
  Because the spacing is close to the 30m clustering radius, DBSCAN may merge
  a series into one cluster or split it, depending on exact spacing.

- **Congested traffic**: Stop-and-go traffic below ~9 mph is common on urban
  arterials and freeway on-ramps during rush hour. The speed gate (15 km/h /
  ~9 mph minimum) means no detection occurs during this time, even if the
  vehicle is traversing severe road damage. This is by design (avoiding
  walking false positives) but means that damage at bottlenecks is
  underreported.

- **Patches and utility cuts**: Roads are frequently patched after utility
  work or freeze-thaw damage. These patches create surface irregularities
  that produce Z deltas of 2-4 m/s^2, below the detection threshold for all
  vehicle types, but which accumulate to a rough-ride experience. The ROUGH_PATCH
  type is intended to capture this, but the current classifier requires at least
  one exceedance of the threshold in the window to register anything.

**Mitigation in v1**: None specific beyond the general noise filters. These are
acknowledged gaps.

**Planned improvements**:
- Low-threshold "background roughness score" computed from Z-axis variance
  during normal driving, aggregated per road segment
- Map integration (e.g. OpenStreetMap railroad crossings and traffic calming
  tags) to distinguish known infrastructure from anomalies

---

## Summary Table

| Limitation | Severity | Workaround in v1 | Planned Fix |
|------------|----------|-------------------|-------------|
| Battery on long trips | Medium | Speed gate | Activity recognition |
| Sensor noise / false positives | Medium | Gyro confirmation | Per-device calibration |
| Device variability | Medium | `device_meta` collection | Dynamic threshold tuning |
| Phone orientation | Resolved | Gravity vector projection (TYPE_GRAVITY) | N/A |
| Data sparsity in rural areas | Medium | Configurable confidence threshold | Incentive mechanisms |
| GPS spoofing | Low | Multi-report confidence | Ingestion anomaly detection |
| Urban GPS accuracy | Medium | `accuracy_m` field | Dead reckoning fusion |
| Long offline gaps | Low | 7-day retention window | Extended retention policy |
| Motorcycle lean and braking | Medium | Raised threshold (5.0 m/s^2) | Turn and lean filter |
| Motorcycle load variation | Low | Backend signal weight (0.8) | Dynamic threshold tuning |
| Speed humps, rail crossings, plates | Low | BUMP classification | Map integration for known infrastructure |
| Congested traffic (<9 mph) | Medium | Acknowledged gap | Low-speed detection mode |
| Background roughness scoring | Medium | Acknowledged gap | Per-segment variance aggregation |
