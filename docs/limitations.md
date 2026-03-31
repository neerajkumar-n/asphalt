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
- Speed gate: sensors are inactive below 15 km/h
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
- 1500ms cooldown between detected events: reduces double-counting of a single
  anomaly

**Remaining constraints**:
- The detection threshold (4.0 m/s^2 default) was set empirically. It is a
  single global value. Optimal thresholds vary by vehicle type (motorcycle vs.
  SUV vs. truck), phone mounting location, and road surface category.
- No frequency-domain analysis. A bandpass filter isolating the 2-10Hz band
  relevant to suspension response would improve SNR, but requires more
  computation and a longer buffer.
- Engine idle vibration at stoplights occasionally triggers false positives when
  the vehicle accelerates sharply from a standing start. The speed gate at 15
  km/h catches most of these, but not all.

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

**Known gap in v1**: The SDK's `handleDetection()` constructs `DeviceMeta()`
with empty defaults in real detections. The fields are only populated correctly
in the demo app's simulated events (`DemoViewModel.simulateBump()`), not in
genuine sensor-triggered events. Populating `DeviceMeta` from `android.os.Build`
at detection time is a planned fix. Until then, all real events arrive with
empty `device_meta` fields and per-model analysis is not possible.

**Remaining constraint**: v1 does not dynamically calibrate per device.
A future version should compute per-device baseline noise floor over the first
few minutes of each session and set the threshold as `noise_floor * K` rather
than a fixed 4.0 m/s^2.

---

## 4. Phone Orientation

**What happens**: The spike-dip detection uses only the Z-axis accelerometer
value. This assumes the phone is lying flat (face up or down) with Z pointing
vertically. In a phone holder attached to the dashboard or windshield, Z may be
the horizontal axis (along the direction of travel), which means the pothole
signal is primarily on X or Y.

**Impact**: In a portrait-orientation phone holder, potholes may not trigger
the Z-axis threshold at all, leading to missed detections. False positives are
less likely because the gyro confirmation still applies.

**Mitigation in v1**: None. The documentation advises laying the phone flat.

**Planned improvement**: Use the gravity vector from the accelerometer (low-pass
filtered) to determine device orientation and project the anomaly signal onto
the correct axis regardless of how the phone is mounted.

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
  device submitting 80 km/h speed data is detectable)

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

## 9. Three-Wheeler Signal Complexity

**What happens**: Auto rickshaws produce a significantly higher noise floor than
cars due to engine vibration, lightweight frame resonance, and tricycle lateral
dynamics. The on-device filters (`ThreeWheelerFilter`) suppress three specific
false-positive patterns: engine vibration, turns, and lateral wobble. However,
the filters are heuristic and edge cases exist.

**Known residual false positives**:

- **CNG vs petrol engines**: The engine vibration zero-crossing filter is
  calibrated for a dominant felt frequency of 15-30 Hz. Compressed natural gas
  (CNG) autos, which are now the majority in many Indian cities, have slightly
  smoother combustion than older petrol 2-strokes. Their vibration profile may
  have a different dominant frequency. The 25 zero-crossings/sec threshold was
  set for older auto profiles; CNG autos may benefit from a lower threshold.

- **Load variation**: An empty auto has a much softer effective suspension than
  a fully loaded one (driver + 3 passengers = ~200kg additional mass). The same
  pothole produces a materially larger Z delta in an empty auto. The threshold
  of 5.5 m/s^2 is calibrated for a loaded auto. An empty auto may report
  intensities that are 20-30% inflated.

- **Pillion passengers on bikes**: Similarly for two-wheelers, a scooter with a
  pillion rider has different suspension compression and body dynamics.

- **Turning on rough roads**: If an auto is both turning AND traversing a rough
  road surface simultaneously (a common scenario at any Indian intersection),
  the turn suppression window may mask a genuine pothole during the turn.
  This is a known missed detection case with no clean solution in v1.

- **Auto with CNG cylinder vibration**: Some autos carry large rear-mounted CNG
  cylinders that add mechanical noise at a different frequency from the engine.
  This can increase the zero-crossing rate even without engine vibration.

**Mitigations in v1**:
- Higher detection threshold (5.5 vs 4.0 m/s^2) reduces false positives
- Backend three-wheeler-only penalty reduces unverified auto clusters
- Cross-vehicle diversity bonus ensures car/bike confirmation compensates

**Planned improvements**:
- Per-operator threshold calibration (fleet operators could run a calibration
  drive on a known smooth road to measure their auto's baseline noise floor)
- Frequency domain analysis (FFT) on the detection window to identify and
  subtract the engine harmonic before applying the threshold
- Load estimation using sustained Z-axis reading as a proxy for suspension
  compression, adjusting the threshold dynamically

---

## 10. Dense Urban Environment Noise

**What happens**: Indian urban road environments combine multiple noise sources
that do not appear in the test conditions where most road detection research
is conducted:

- **Speed breakers without signage**: Unofficial speed bumps built by residents
  are common in Indian cities. They vary in height (5cm to 20cm) and profile.
  Many are not in any map database, so there is no way to pre-filter known bumps.
  These are detected as genuine anomalies, which is correct behaviour, but they
  can dominate the map in some localities.

- **Railway crossings**: Level crossings create a distinctive double-impact
  signature (two rails, spaced ~1.4m apart). At 30 km/h, the two impacts
  arrive ~170ms apart. The detector may report this as a single pothole event
  or two separate events depending on cooldown timing.

- **Speed humps in series**: Traffic calming installations often use 3-4 speed
  bumps in quick succession (every 20-30m). At 30 km/h, these arrive every
  ~2.4 seconds. The 2-second cooldown for three-wheelers means the first bump
  suppresses detection of subsequent ones. Not all bumps in a series will be
  reported.

- **Congested traffic**: Stop-and-go traffic at speeds of 5-15 km/h is common
  in Indian cities during peak hours. The speed gate (15 km/h minimum) means no
  detection occurs during this time, even if the vehicle is traversing severe
  road damage. This is by design (avoiding walking false positives) but means
  that damage at bottlenecks in congested areas is underreported.

- **Dust and road patches**: Indian roads are frequently patched with tar,
  gravel, or concrete infill. These patches create surface irregularities
  that produce Z deltas of 2-4 m/s^2, below the detection threshold for all
  vehicle types, but which accumulate to a rough-ride experience. The ROUGH_PATCH
  type is intended to capture this, but the current classifier requires at least
  one exceedance of the threshold in the window to register anything.

**Mitigation in v1**: None specific beyond the general noise filters. These are
acknowledged gaps.

**Planned improvements**:
- Low-threshold "background roughness score" computed from Z-axis variance
  during normal driving, aggregated per road segment
- Map integration to distinguish known infrastructure (railway crossings,
  official speed bumps) from anomalies

---

## Summary Table

| Limitation | Severity | Workaround in v1 | Planned Fix |
|------------|----------|-------------------|-------------|
| Battery on long trips | Medium | Speed gate | Activity recognition |
| Sensor noise / false positives | Medium | Gyro confirmation | Per-device calibration |
| Device variability | Medium | `device_meta` collection | Dynamic threshold tuning |
| Phone orientation | High | Documentation | Gravity vector projection |
| Data sparsity in rural areas | Medium | Configurable confidence threshold | Incentive mechanisms |
| GPS spoofing | Low | Multi-report confidence | Ingestion anomaly detection |
| Urban GPS accuracy | Medium | `accuracy_m` field | Dead reckoning fusion |
| Long offline gaps | Low | 7-day retention window | Extended retention policy |
| Three-wheeler engine vibration | Medium | Zero-crossing filter | FFT harmonic removal |
| Three-wheeler load variation | Medium | Raised threshold (5.5 m/s^2) | Dynamic threshold tuning |
| Turn masking genuine potholes | Low | Turn suppression duration tuning | Directional turn model |
| Dense urban speed bumps | Low | BUMP classification | Map integration for known humps |
| Congested traffic (<15 km/h) | Medium | Acknowledged gap | Lower threshold mode for autos |
| Background roughness scoring | Medium | Acknowledged gap | Per-segment variance aggregation |
