# Vehicle Profiles

Asphalt supports two vehicle categories: four-wheelers (cars, SUVs, pickups)
and two-wheelers (motorcycles, scooters, mopeds). Each produces a distinct vibration
environment that requires different signal processing to separate genuine road
anomalies from normal vehicle dynamics.

---

## Why Vehicle Type Matters

A single global detection threshold cannot serve all vehicles. The noise floors
differ by category:

| Signal | Four-wheeler | Two-wheeler |
|--------|-------------|-------------|
| Z-axis baseline noise | 0.1-0.5 m/s^2 | 0.5-1.5 m/s^2 |
| Lateral gyro on straight road | <0.2 rad/s | 0.2-0.4 rad/s |
| Detection threshold (default) | 4.0 m/s^2 | 5.0 m/s^2 |
| Signal weight (backend) | 1.0 | 0.8 |

Setting the four-wheeler threshold of 4.0 m/s^2 for a motorcycle would let
lean changes and handlebar vibration through as false events. Setting the
motorcycle threshold of 5.0 m/s^2 for a car would miss mild potholes.

---

## Four-Wheelers (Cars, SUVs, Pickups)

**Mechanics**: Four contact points. Independent suspension or McPherson struts.
Engine mounted with rubber bushings that isolate vibration from the cabin.
Modern cars have effective noise isolation; the phone rests in a stable
environment.

**Signal characteristics**:
- Z-axis at cruise: 9.81 +/- 0.1-0.3 m/s^2
- Pothole at 30 mph: Z delta of 4-8 m/s^2
- Gyro on straight road: <0.2 rad/s magnitude
- Gyro during pothole: 0.4-1.0 rad/s (pitch)

**Profile settings**:
- Threshold: 4.0 m/s^2
- Baseline window: 64 samples (~1.3 seconds at 50Hz)
- Gyro confirmation: 0.3 rad/s
- Cooldown between events: 1500ms

---

## Two-Wheelers (Motorcycles, Scooters, Mopeds)

**Mechanics**: Single track. Stability maintained by gyroscopic effect of wheels
and rider lean angle. Engine vibration is transmitted through the frame to the
handlebars and seat more directly than in cars.

**Key differences from cars**:
- **Lean angle during turns**: A motorcycle at 25 mph on a corner may lean 20-30
  degrees. This shifts the gravity vector, reducing Z-axis reading by
  cos(lean_angle) * g. A 20-degree lean reduces Z from 9.81 to 9.22 m/s^2 -- a
  delta of 0.59 m/s^2 from lean alone, before any road anomaly is involved.
- **Higher sensitivity at low speed**: Scooters and delivery mopeds in
  stop-and-go city traffic frequently traverse speed bumps and rough patches that cars glide over because the
  smaller wheels compress into defects more. This makes two-wheelers sensitive
  sensors, but the signal-to-noise ratio is lower.
- **Rider dynamics**: Rider posture, braking, and acceleration all modulate the
  Z-axis signal. Aggressive braking causes a forward pitch that looks like the
  spike phase of a pothole.

**Profile settings**:
- Threshold: 5.0 m/s^2
- Gyro confirmation threshold: 0.45 rad/s
- Baseline window: 80 samples (~1.6 seconds)
- Cooldown between events: 1500ms

There is no dedicated lean or turn filter yet. Cornering lean is handled only
by the raised threshold and wider baseline window. A motorcycle-specific turn
filter is on the roadmap.

---

## Backend: Cross-Vehicle Aggregation

### Normalised intensity

When computing cluster average intensity, each event's contribution is weighted
by its vehicle signal weight:
```
weighted_avg_intensity = sum(intensity[i] * weight[i]) / sum(weight[i])

Example cluster:
  Event A: motorcycle, intensity 0.6, weight 0.8  -> contribution 0.48
  Event B: motorcycle, intensity 0.7, weight 0.8  -> contribution 0.56
  Event C: car,        intensity 0.5, weight 1.0  -> contribution 0.50

  weighted_avg = (0.48 + 0.56 + 0.50) / (0.8 + 0.8 + 1.0)
               = 1.54 / 2.6
               = 0.592
```

Without weighting, the cluster avg would be (0.6 + 0.7 + 0.5)/3 = 0.60.
The weighted result (0.59) is marginally lower, reflecting that two of the
three events came from a noisier source. The difference is small but consistent.

### Confidence scoring and vehicle diversity

The confidence formula rewards cross-vehicle confirmation:

```
base_confidence = countScore * 0.50 + consistencyScore * 0.25 + recencyScore * 0.15

diversity_bonus = 0.08 * (distinct_vehicle_types - 1)

confidence = base_confidence + diversity_bonus
```

**Example: motorcycle-only cluster (3 events)**
```
total_weight = 3 * 0.8 = 2.4
effective_count = 2.4
countScore = log10(3.4) / log10(21) = 0.531 / 1.322 = 0.402
consistencyScore = 1.0 (all "pothole")
recencyScore = 1.0 (recent)
diversity_bonus = 0 (1 vehicle type)

confidence = 0.402*0.50 + 1.0*0.25 + 1.0*0.15 + 0
           = 0.201 + 0.25 + 0.15
           = 0.601
```

**Example: mixed cluster (2 motorcycles + 1 car)**
```
total_weight = 2*0.8 + 1*1.0 = 2.6
effective_count = 2.6
countScore = log10(3.6) / log10(21) = 0.556 / 1.322 = 0.421
consistencyScore = 1.0
recencyScore = 1.0
diversity_bonus = 0.08 (2 distinct types)

confidence = 0.421*0.50 + 1.0*0.25 + 1.0*0.15 + 0.08
           = 0.210 + 0.25 + 0.15 + 0.08
           = 0.690
```

Three events from mixed types (conf=0.69) are treated as more reliable than
three events from motorcycles alone (conf=0.60) or from cars alone (conf=0.63).
The diversity bonus (+0.08) rewards independent confirmation from vehicles
with different suspension and noise characteristics.

### Display threshold recommendation

The tile API (`GET /v1/map/tiles/{z}/{x}/{y}`) defaults to `min_confidence=0.3`
if the parameter is not supplied. The bbox query (`GET /v1/map/clusters`) returns
all clusters regardless of confidence and leaves filtering to the client.

Recommended values by use case:

| Use case | Recommended min_confidence |
|----------|---------------------------|
| Consumer map (API default) | 0.30 |
| Consumer map (noise-reduced) | 0.40 |
| Municipal road authority | 0.30 (more inclusive) |
| Fleet routing (high precision) | 0.60 |
| Emergency pothole alert | 0.25 (maximise recall) |

For early deployments, staying at the default 0.30 is recommended. Raising to
0.40 may hide genuine clusters that only have a few motorcycle reports, before
cross-vehicle corroboration builds up.

---

## Setting Vehicle Type in Your App

```kotlin
// Motorcycle / moped delivery app
Asphalt.init(this, AsphaltConfig(
    ingestUrl = "https://api.yourserver.com/v1/ingest/batch",
    vehicleType = VehicleType.TWO_WHEELER
))

// Consumer app with vehicle selection
val vehicleType = when (userSelectedVehicle) {
    "motorcycle", "scooter", "moped" -> VehicleType.TWO_WHEELER
    else                             -> VehicleType.FOUR_WHEELER
}
Asphalt.init(this, AsphaltConfig(
    ingestUrl = "https://api.yourserver.com/v1/ingest/batch",
    vehicleType = vehicleType
))
```

The vehicle type cannot be changed after `Asphalt.start()` is called in a
session. Call `Asphalt.stop()`, update the config via `Asphalt.setConfig()`,
then call `Asphalt.start()` again:

```kotlin
Asphalt.stop()
Asphalt.setConfig(currentConfig.copy(vehicleType = VehicleType.TWO_WHEELER))
Asphalt.start()
```

Do not call `Asphalt.init()` again after initial setup -- `setConfig()` is
the correct runtime API for config updates. Re-calling `init()` from an
activity would set `appContext` again unnecessarily and could produce
unexpected behaviour if the Application class has already configured the SDK.
