# Sensor Model

This document explains the physics behind road anomaly detection and the
heuristics used by the Asphalt SDK.

---

## Why the Accelerometer Detects Vertical Displacement

A smartphone accelerometer measures *specific force*: the acceleration of the
device relative to free fall, in metres per second squared (m/s^2).

At rest on a flat surface, the sensor reads gravity on the vertical axis:
approximately +9.81 m/s^2 on the Z axis (when the phone faces upward).

When a vehicle drives over a surface, the sensor reads a combination of:
- The constant gravity component (~9.81 m/s^2)
- Any acceleration caused by road surface changes

On a perfectly flat road at constant speed, the Z reading is stable around
9.81 with small noise (~0.1-0.3 m/s^2 from engine vibration and tyre roll).

When the road surface causes a sudden vertical displacement (up or down), the
car body accelerates vertically. The accelerometer detects this as a deviation
from the 9.81 baseline.

---

## Pothole Signal: Spike-Dip Signature

A pothole creates a characteristic two-part Z-axis signature:

```
Z-axis (m/s^2)
  ^
  |
16|           *
15|          * *
14|         *   *
13|        *     *
12|       *       *
11|      *         *
10|     *           *
 9.81-*---------baseline---------*-----
 9|                               *
 8|                                *
 7|                                 *
 6|                                  *
  +---+---+---+---+---+---+---+---+---->  time (ms)
  0  50 100 150 200 250 300 350 400

  [  flat road  ] [ DIP ] [SPIKE] [ recovery ]
```

**Phase 1 - Dip (wheel enters pothole):**
The front tyre drops into the hole. The car body does not immediately follow
because the suspension briefly extends upward. The body momentarily experiences
*reduced* downward force. The accelerometer Z value drops below baseline,
sometimes to 6-7 m/s^2.

**Phase 2 - Spike (wheel strikes far edge and rebounds):**
The tyre hits the far wall of the pothole and the suspension compresses
sharply. The car body is pushed upward. The accelerometer reads *increased*
downward force, often 14-18 m/s^2 for a moderate pothole.

**Phase 3 - Recovery:**
The suspension absorbs the rebound. Z oscillates briefly and returns to 9.81.

The total duration at 50 km/h is roughly 150-400ms. At higher speeds the
event is shorter in time but produces higher peak values because the wheel
traverses the same physical defect in less time with more kinetic energy.

---

## Speed Bump Signature: Dip-Spike (Inverted Order)

An intentional speed bump (traffic calming device) creates the opposite
temporal ordering:

```
Z-axis (m/s^2)
  ^
16|    *
15|   * *
14|  *   *
13| *     *
  9.81 baseline
 8|            *
 7|             *
 6|              *
  +---time--->
  [climb face] [descent]
```

The front wheel climbs the bump face first (Z increases, spike), then the
tyre drops off the rear of the bump (Z decreases, dip).

The `classifySignature` function in `AnomalyDetector` uses the temporal
index of the peak vs. the dip within the detection window to distinguish
pothole from bump:
- `dip_index < peak_index` -> POTHOLE
- `peak_index < dip_index` -> BUMP

---

## Rough Patch Signature

A rough patch (damaged road surface without a discrete void) does not produce
a clear spike-dip pair. Instead, Z variance is elevated throughout the window
without a dominant single peak. The classifier identifies this by checking
that neither the peak deviation nor the dip deviation is large enough to
qualify as pothole or bump.

```
Z-axis (m/s^2)
  ^
12|  *   *     *
11|   * * * * * *
10|* *          * *
9.81 baseline........
  +--------time-------->
```

---

## Gyroscope Role

The gyroscope measures angular velocity around each axis in radians per second
(rad/s).

A physical road anomaly causes the vehicle body to pitch (rotate around the
lateral Y axis) and sometimes roll (rotate around the longitudinal X axis).
When a wheel drops into a pothole, the front of the car pitches downward
briefly. This produces a measurable gyroscope signal.

Sensor noise does NOT produce gyroscope response. Specifically:
- Engine idle vibration: high-frequency Z oscillation, near-zero gyro
- Music/bass vibration: same pattern
- A loose phone mount vibrating: accelerometer sees noise, gyro near zero
- Cargo moving inside the car: gyro may spike but accelerometer pattern
  does not match spike-dip

The SDK requires `gyro_peak_magnitude > 0.3 rad/s` (configurable) during
the detection window before reporting an event. This single check eliminates
a large fraction of false positives in practice.

---

## GPS Speed Filtering

GPS provides the vehicle speed via `Location.speed` (metres per second).

The SDK refuses to activate sensor collection below 15 km/h. This threshold
eliminates:

| Scenario | Speed range | Z variation | Why filtered |
|----------|------------|------------|--------------|
| Walking  | 3-6 km/h   | 2-4 m/s^2  | Gait creates rhythmic Z oscillation at ~2Hz |
| Slow traffic | 5-15 km/h | 1-3 m/s^2 | Kerbs, speed bumps at creep speed look like potholes |
| Parking manoeuvre | 0-5 km/h | High | Phone orientation changes during parking |
| Stationary vehicle | 0 km/h | Variable | Engine vibration, door slams |

At speeds above 15 km/h, the pedestrian gait pattern disappears and the
vehicle dynamics become the dominant signal source.

The speed value also feeds the intensity normalisation. A 6 m/s^2 delta at
30 km/h is a more severe anomaly than the same delta at 80 km/h (the vehicle
had less kinetic energy to generate the G force at low speed).

---

## Sample Signal Patterns

### Moderate Pothole at 50 km/h

```
t=0ms    Z=9.83  (baseline, smooth road)
t=20ms   Z=9.79
t=40ms   Z=9.76
t=60ms   Z=9.80
t=80ms   Z=8.92  (approaching, suspension begins to extend)
t=100ms  Z=7.41  (DIP: wheel in hole)
t=120ms  Z=6.83  (dip minimum)
t=140ms  Z=8.15  (wheel striking far edge)
t=160ms  Z=12.44 (rebound begins)
t=180ms  Z=15.62 (SPIKE: maximum, suspension compressed)
t=200ms  Z=13.81
t=220ms  Z=11.20
t=240ms  Z=10.03 (returning to baseline)
t=260ms  Z=9.85
t=280ms  Z=9.82

Gyro Y at t=100-200ms: 0.65 rad/s (pitch during event)

AnomalyDetector output:
  - baseline Z: 9.81 (rolling median)
  - peak Z: 15.62
  - dip Z: 6.83
  - delta: 5.81 m/s^2 (exceeds 4.0 threshold)
  - gyro peak: 0.65 rad/s (exceeds 0.3 threshold)
  - dip index (6): before peak index (9) -> POTHOLE
  - intensity: (5.81 / 12.0) * 1.0 (speed 50km/h) = 0.48
```

### False Positive Filtered by Gyroscope

```
t=0ms    Z=9.81  (baseline)
t=120ms  Z=15.2  (music bass thump in car, large speaker)
t=140ms  Z=9.83

Gyro magnitude: 0.08 rad/s (near zero - no physical rotation)

AnomalyDetector output:
  - delta: 5.39 m/s^2 (would exceed threshold)
  - gyro peak: 0.08 rad/s (BELOW 0.3 threshold)
  -> REJECTED: no event recorded
```

---

## Known Signal Limitations

- **Phone orientation**: The Z-axis reading assumes the phone is lying flat
  (face up) or in a consistent orientation. If the phone is vertical (portrait
  in a holder), the X or Y axis carries the gravity component. Version 1 does
  not compensate for arbitrary orientation. A gravity-subtracted resultant
  vector approach would generalise better, at the cost of complexity.

- **Phone mounting rigidity**: A loose phone mount attenuates the signal.
  A phone bouncing in a cupholder may record every bump with exaggerated
  amplitude. There is no runtime detection of mount quality.

- **Sensor calibration variance**: Accelerometer sensitivity and offset vary
  by manufacturer and model. A delta of 4.0 m/s^2 on a Pixel may correspond
  to 3.5 m/s^2 on a Samsung Galaxy due to factory calibration differences.
  The `device_meta.manufacturer` field in event data allows backend analysis
  to identify and correct for per-model offsets over time.

- **Road type**: Speed bumps and rail crossings produce signals similar to
  potholes. The temporal ordering heuristic reduces misclassification but
  does not eliminate it.
