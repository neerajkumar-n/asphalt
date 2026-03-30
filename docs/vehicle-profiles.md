# Vehicle Profiles

Asphalt supports three vehicle categories. Each produces a distinct vibration
environment that requires different signal processing to separate genuine road
anomalies from normal vehicle dynamics.

---

## Why Vehicle Type Matters

A single global detection threshold cannot serve all vehicles. The noise floors
differ by category:

| Signal | Four-wheeler | Three-wheeler | Two-wheeler |
|--------|-------------|---------------|-------------|
| Z-axis baseline noise | 0.1-0.5 m/s^2 | 1.5-3.0 m/s^2 | 0.5-1.5 m/s^2 |
| Lateral gyro on straight road | <0.2 rad/s | 0.3-0.6 rad/s | 0.2-0.4 rad/s |
| Detection threshold (default) | 4.0 m/s^2 | 5.5 m/s^2 | 5.0 m/s^2 |
| Signal weight (backend) | 1.0 | 0.7 | 0.8 |

Setting the four-wheeler threshold of 4.0 m/s^2 for an auto would produce
an event every few seconds from engine vibration alone. Setting the auto
threshold of 5.5 m/s^2 for a car would miss mild potholes.

---

## Four-Wheelers (Cars, SUVs)

**Mechanics**: Four contact points. Independent suspension or McPherson struts.
Engine mounted with rubber bushings that isolate vibration from the cabin.
Modern cars have effective noise isolation; the phone rests in a stable
environment.

**Signal characteristics**:
- Z-axis at cruise: 9.81 +/- 0.1-0.3 m/s^2
- Pothole at 50 km/h: Z delta of 4-8 m/s^2
- Gyro on straight road: <0.2 rad/s magnitude
- Gyro during pothole: 0.4-1.0 rad/s (pitch)

**Profile settings**:
- Threshold: 4.0 m/s^2
- Baseline window: 64 samples (~1.3 seconds at 50Hz)
- Gyro confirmation: 0.3 rad/s
- Turn suppression: >0.8 rad/s sustained for >300ms

---

## Three-Wheelers (Auto Rickshaws)

Auto rickshaws are the backbone of last-mile transport in Indian cities.
Millions operate daily in cities like Mumbai, Delhi, Bengaluru, Chennai,
Hyderabad, and Kolkata. They cover road segments that cars avoid and take
routes that no other vehicle uses consistently.

This makes them uniquely valuable as road quality sensors, and uniquely
challenging to process.

### Mechanical structure

```
     [FRONT WHEEL]
          |
    ------+------     <-- steering column
    |           |
    |  ENGINE   |
    |           |
    [R1]     [R2]     <-- two rear wheels
```

- One front wheel steers; two rear wheels drive
- This creates a tricycle geometry that is inherently less stable than a car
  above ~40 km/h
- The frame is lightweight tubular steel or aluminium, less rigid than a car chassis
- Engine (typically 3-cylinder CNG or petrol, or older 2-stroke single cylinder)
  is mounted under the passenger compartment without heavy rubber isolation

### Why the noise floor is high

**Engine vibration**:
A 3-cylinder 4-stroke engine at 2000 RPM fires at:
```
2000 RPM / 60 = 33.3 revolutions per second
3 cylinders x 0.5 (4-stroke: 1 fire per 2 revolutions) = 1.5 fires per revolution
33.3 x 1.5 = 50 Hz firing frequency
```
But after coupling through the lightweight frame and into a phone mount,
the felt frequency is typically 15-30 Hz. The rolling median baseline
(96 samples, ~1.9 seconds at 50Hz) averages out this periodic signal so the
baseline remains stable despite the oscillation.

**Lateral instability**:
The single front wheel acts as a pivot point. At urban speeds (15-30 km/h),
small road irregularities cause the body to rock side to side. This produces
sustained gyroscope roll (X axis) at 0.3-0.6 rad/s even on roads with no
significant anomalies. Without suppression, every block would register dozens
of false "rough patch" events.

### The ThreeWheelerFilter

Three suppression mechanisms are applied in order:

**1. Engine vibration filter (zero-crossing rate)**
```
Pothole impact signal:
Z: 9.81 -> 6.5 -> 15.2 -> 10.1 -> 9.81   (2-4 zero crossings in 500ms = 4-8 per sec)

Engine vibration signal:
Z: 9.81 -> 11.2 -> 8.4 -> 11.1 -> 8.5 ...   (periodic, ~40 zero crossings per sec)
```
The filter counts zero-crossings of the AC component (Z minus rolling baseline)
in the last 500ms. Values above 25 crossings/sec indicate periodic vibration,
not an impact. The event is suppressed.

**2. Turn suppression**
When the auto negotiates a turn:
- Gyro Z (yaw): 0.4-0.7 rad/s sustained
- Gyro X (roll): 0.4-0.7 rad/s sustained (body leans outward)
- Duration: 500-2500ms

If either roll or yaw exceeds 0.55 rad/s for more than 250ms, event detection
is suppressed for the turn duration plus a 1200ms settling window after the
turn ends.

**3. Lateral wobble suppression**
At 20-30 km/h on rough roads, autos wobble side-to-side continuously without
turning. This produces sustained roll (gyro X) > 0.55 rad/s without a
corresponding yaw component. The same sustained-elevation check catches this
and suppresses it as LATERAL_WOBBLE.

### What gets through to the detector

After these filters, the remaining signal has:
- Short-duration gyro spikes (< 250ms) associated with large Z deltas
- Z delta > 5.5 m/s^2 (above the 4.0 threshold used for cars)
- Gyro confirmation > 0.55 rad/s

A genuine pothole on an auto at 30 km/h typically reads:
- Z delta: 6-10 m/s^2 (autos transfer more of the road shock than cars because their suspension is simpler)
- Gyro: 0.6-1.2 rad/s briefly (100-200ms)
- Zero-crossing rate: 4-8 per second (a single impact, not periodic)

### Sample signal comparison: pothole vs engine vibration on auto

```
POTHOLE AT 30 KM/H (auto rickshaw):

Time (ms)  Z (m/s^2)   Gyro-mag (rad/s)   Filter result
  0         9.85        0.12               ---
 20         9.82        0.11               ---
 40         9.78        0.13               ---
 60         8.10        0.31               ---
 80         6.70        0.82               ---   <- dip
100         7.90        1.10               ---
120        14.30        1.05               ---   <- spike
140        11.20        0.74               ---
160         9.95        0.38               ---
180         9.83        0.15               ---
  Zero-crossings in 500ms window: 5 (~10/sec) -> BELOW threshold
  Gyro peak: 1.10 -> ABOVE 0.55 threshold
  Z delta: 5.49 -> ABOVE 5.5 threshold (just barely)
  -> EVENT REPORTED (pothole, intensity 0.52)

ENGINE VIBRATION AT IDLE (auto rickshaw):

Time (ms)  Z (m/s^2)   Gyro-mag (rad/s)
  0        10.92        0.18
 20         8.71        0.16
 40        11.05        0.19
 60         8.68        0.17
 80        10.98        0.19
100         8.72        0.16
120        11.03        0.18
  Zero-crossings in 500ms window: 36 (~72/sec) -> ABOVE 25 threshold
  -> SUPPRESSED (ENGINE_VIBRATION)
```

---

## Two-Wheelers (Motorcycles, Scooters)

**Mechanics**: Single track. Stability maintained by gyroscopic effect of wheels
and rider lean angle. Engine vibration is transmitted through the frame to the
handlebars and seat more directly than in cars.

**Key differences from cars**:
- **Lean angle during turns**: A motorcycle at 40 km/h on a corner may lean 20-30
  degrees. This shifts the gravity vector, reducing Z-axis reading by
  cos(lean_angle) * g. A 20-degree lean reduces Z from 9.81 to 9.22 m/s^2 -- a
  delta of 0.59 m/s^2 from lean alone, before any road anomaly is involved.
- **Higher sensitivity at low speed**: Scooters in stop-and-go traffic frequently
  traverse speed bumps and rough patches that cars glide over because the
  smaller wheels compress into defects more. This makes two-wheelers sensitive
  sensors, but the signal-to-noise ratio is lower.
- **Rider dynamics**: Rider posture, braking, and acceleration all modulate the
  Z-axis signal. Aggressive braking causes a forward pitch that looks like the
  spike phase of a pothole.

**Profile settings**:
- Threshold: 5.0 m/s^2
- Baseline window: 80 samples (~1.6 seconds)
- Turn suppression: >0.5 rad/s sustained for >200ms

---

## Backend: Cross-Vehicle Aggregation

### Normalised intensity

When computing cluster average intensity, each event's contribution is weighted
by its vehicle signal weight:
```
weighted_avg_intensity = sum(intensity[i] * weight[i]) / sum(weight[i])

Example cluster:
  Event A: auto, intensity 0.6, weight 0.7  -> contribution 0.42
  Event B: auto, intensity 0.7, weight 0.7  -> contribution 0.49
  Event C: car,  intensity 0.5, weight 1.0  -> contribution 0.50

  weighted_avg = (0.42 + 0.49 + 0.50) / (0.7 + 0.7 + 1.0)
               = 1.41 / 2.4
               = 0.5875
```

Without weighting, the cluster avg would be (0.6 + 0.7 + 0.5)/3 = 0.60.
The weighted result (0.59) is marginally lower, reflecting that two of the
three events came from a noisier source. The difference is small but consistent.

### Confidence scoring and vehicle diversity

The confidence formula rewards cross-vehicle confirmation:

```
base_confidence = countScore * 0.50 + consistencyScore * 0.25 + recencyScore * 0.15

diversity_bonus = 0.08 * (distinct_vehicle_types - 1)

three_wheeler_penalty = -0.08 if (only_autos AND event_count < 4) else 0

confidence = base_confidence + diversity_bonus + three_wheeler_penalty
```

**Example: auto-only cluster (3 events)**
```
total_weight = 3 * 0.7 = 2.1
effective_count = 2.1
countScore = log10(3.1) / log10(21) = 0.491 / 1.322 = 0.371
consistencyScore = 1.0 (all "pothole")
recencyScore = 1.0 (recent)
diversity_bonus = 0 (1 vehicle type)
three_wheeler_penalty = -0.08 (only autos, < 4 events)

confidence = 0.371*0.50 + 1.0*0.25 + 1.0*0.15 + 0 - 0.08
           = 0.186 + 0.25 + 0.15 - 0.08
           = 0.506
```

**Example: mixed cluster (2 autos + 1 car)**
```
total_weight = 2*0.7 + 1*1.0 = 2.4
effective_count = 2.4
countScore = log10(3.4) / log10(21) = 0.531 / 1.322 = 0.402
consistencyScore = 1.0
recencyScore = 1.0
diversity_bonus = 0.08 (2 distinct types)
three_wheeler_penalty = 0 (not auto-only)

confidence = 0.402*0.50 + 1.0*0.25 + 1.0*0.15 + 0.08 - 0
           = 0.201 + 0.25 + 0.15 + 0.08
           = 0.681
```

Three events from mixed types (conf=0.68) are treated as significantly more
reliable than three events from autos alone (conf=0.51). The diversity bonus
(+0.08) and absence of the three-wheeler penalty (+0.08) together add 0.17
to the confidence of the mixed cluster.

### Display threshold recommendation

| Use case | Recommended min_confidence |
|----------|---------------------------|
| Default consumer map | 0.40 |
| Municipal road authority | 0.30 (more inclusive) |
| Fleet routing (high precision) | 0.60 |
| Emergency pothole alert | 0.25 (maximise recall) |

For deployments in dense Indian urban areas where three-wheelers are the primary
vehicle type, lowering the threshold to 0.30 is recommended to avoid excessive
data sparsity from the three-wheeler penalty.

---

## Setting Vehicle Type in Your App

```kotlin
// Auto rickshaw fleet management app
Asphalt.init(this, AsphaltConfig(
    ingestUrl = "https://api.yourserver.com/v1/ingest/batch",
    vehicleType = VehicleType.THREE_WHEELER
))

// Consumer app with vehicle selection
val vehicleType = when (userSelectedVehicle) {
    "bike", "scooter" -> VehicleType.TWO_WHEELER
    "auto", "tuk-tuk"  -> VehicleType.THREE_WHEELER
    else               -> VehicleType.FOUR_WHEELER
}
Asphalt.init(this, AsphaltConfig(
    ingestUrl = "https://api.yourserver.com/v1/ingest/batch",
    vehicleType = vehicleType
))
```

The vehicle type cannot be changed after `Asphalt.start()` is called in a
session. Call `Asphalt.stop()` and re-initialise with a new config to change it.
