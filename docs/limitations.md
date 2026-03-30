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
- `device_meta.manufacturer` and `device_meta.model` in event payload: allows
  backend analysis to identify per-model calibration offsets from crowd data
- `device_meta.sensor_vendor`: accelerometer vendor (e.g. InvenSense, Bosch)
  correlates with noise characteristics

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
