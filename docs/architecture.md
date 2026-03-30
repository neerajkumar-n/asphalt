# Asphalt Architecture

## Overview

Asphalt is a pipeline from raw smartphone sensors to a queryable road quality
map. It is designed around three constraints: battery, privacy, and noise.

The system is composed of:
1. An Android SDK that runs on users' devices during driving
2. A backend that ingests, stores, clusters, and serves anomaly data
3. A query API for map clients

---

## System Architecture Diagram

```mermaid
flowchart TD
    subgraph Device["Android Device"]
        direction TB

        subgraph Sensors["Sensor Layer"]
            ACCEL["Accelerometer\n50Hz, Z-axis primary"]
            GYRO["Gyroscope\n50Hz, confirmation"]
            GPS["FusedLocationProvider\n1Hz, speed + coordinates"]
        end

        subgraph Preprocessing["On-Device Preprocessing"]
            SPEED_GATE["Speed Gate\n>15 km/h required\nActivates/deactivates sensors"]
            BASELINE["Rolling Baseline\nMedian of last 64 samples\nSubtracts gravity + road noise"]
        end

        subgraph Detection["Event Detection"]
            SPIKE_DIP["Spike-Dip Detector\nZ-axis delta threshold\n4.0 m/s^2 default"]
            GYRO_CONFIRM["Gyro Confirmation\nMagnitude >0.3 rad/s\nFilters sensor noise"]
            CLASSIFIER["Signature Classifier\nTemporal ordering of peak/dip\nPothole vs Bump vs Rough"]
            INTENSITY["Intensity Scorer\nNormalised 0.0-1.0\nSpeed-adjusted"]
        end

        subgraph Storage["Offline Buffer"]
            ROOM["Room Database\nSQLite on-device\nEvents stored before upload"]
        end

        subgraph Upload["Upload Layer"]
            WORKER["WorkManager\nPeriodic flush (5 min)\nBatches up to 500 events\nExponential backoff retry"]
        end
    end

    subgraph Backend["Backend (Go)"]
        direction TB

        subgraph Ingestion["Ingestion Service"]
            INGEST_API["POST /v1/ingest/batch\nHTTP/JSON\nIdempotent via batch_id"]
            VALIDATOR["Event Validator\nSchema + range checks\nTimestamp sanity"]
            DEDUP["Batch Deduplication\nbatch_id table lookup"]
        end

        subgraph Processing["Processing Pipeline"]
            PG[("PostgreSQL + PostGIS\nroad_events table\nSpatial index on geom")]
            CLUSTER_WORKER["Clustering Worker\nDBSCAN, 30m radius\nRuns every 5 minutes"]
            CLUSTER_TABLE[("anomaly_clusters table\nConfidence scored\nUpdated incrementally")]
        end

        subgraph QueryAPI["Query API"]
            TILE_API["GET /v1/map/tiles/{z}/{x}/{y}\nSlippy tile bounds -> clusters"]
            BBOX_API["GET /v1/map/clusters\nBounding box query"]
        end
    end

    subgraph Clients["Map Clients"]
        MAP_APP["Mobile or Web Map\nRenders cluster markers\nColour by intensity"]
    end

    %% Sensor -> Preprocessing
    ACCEL --> SPEED_GATE
    GPS --> SPEED_GATE
    SPEED_GATE --> BASELINE

    %% Preprocessing -> Detection
    BASELINE --> SPIKE_DIP
    GYRO --> GYRO_CONFIRM
    SPIKE_DIP --> GYRO_CONFIRM
    GYRO_CONFIRM --> CLASSIFIER
    CLASSIFIER --> INTENSITY

    %% Detection -> Storage
    INTENSITY --> ROOM

    %% Storage -> Upload
    ROOM --> WORKER

    %% Upload -> Backend
    WORKER -->|"HTTPS JSON batch"| INGEST_API

    %% Ingestion pipeline
    INGEST_API --> DEDUP
    DEDUP --> VALIDATOR
    VALIDATOR --> PG

    %% Clustering
    PG --> CLUSTER_WORKER
    CLUSTER_WORKER --> CLUSTER_TABLE

    %% Query
    CLUSTER_TABLE --> TILE_API
    CLUSTER_TABLE --> BBOX_API
    TILE_API --> MAP_APP
    BBOX_API --> MAP_APP
```

---

## Component Breakdown

### Sensor Layer

Three sensors are used in combination:

| Sensor | Purpose | Sample Rate |
|--------|---------|-------------|
| Accelerometer | Primary anomaly signal (Z-axis vertical displacement) | 50Hz |
| Gyroscope | Motion validation, filters sensor noise | 50Hz |
| GPS (FLP) | Speed gate, event geolocation | 1Hz |

All three must agree before an event is recorded. This is the sensor fusion
principle: no single sensor is trusted alone.

### Speed Gate

The speed gate is the most impactful battery optimisation. Accelerometer and
gyroscope are unregistered below 15 km/h. At 50Hz sampling, each sensor draws
approximately 0.5-1.5mA continuously. Inactive sensors draw near zero.

On a typical urban drive (60% of time above threshold), this halves sensor
power consumption compared to always-on collection.

### On-Device Detection

Detection runs synchronously on the sensor callback thread. It is designed to
complete in under 1ms to avoid dropping sensor samples. No blocking I/O, no
network calls, no heavy allocation.

Events are written to Room (SQLite) asynchronously on a background coroutine.

### Offline Buffer

WorkManager handles all upload scheduling. The SDK never opens a network
connection synchronously. This means:
- Detection works with no network connectivity
- Uploads happen on the device's schedule (respecting Doze mode)
- Failed uploads are automatically retried with exponential backoff

### Backend

Go was chosen over Node.js for the following reasons:

1. **Concurrency model**: Go's goroutines handle thousands of simultaneous
   batch uploads with low memory overhead. Each request handler runs in its
   own goroutine; the runtime schedules them efficiently on available cores.

2. **Predictable latency**: Go's GC is designed for low-pause operation.
   Ingestion latency must be consistent to avoid client timeout-related retries.

3. **Single binary deployment**: No runtime dependency (no JVM, no Node
   version manager). The Dockerfile produces a ~7MB static binary.

4. **Standard library strength**: `net/http`, `database/sql`, and `encoding/json`
   cover all needs without heavy third-party dependencies. Less surface area
   to audit for security vulnerabilities.

The backend is stateless. All state lives in PostgreSQL. Horizontal scaling
requires only adding more container replicas behind a load balancer.

### Clustering Pipeline

The clustering worker runs DBSCAN on unclustered events every 5 minutes.
DBSCAN was chosen because:
- Road anomalies do not form round clusters; they form linear clusters
  along road segments. DBSCAN finds arbitrary shapes.
- No need to specify the number of clusters.
- Noise points (isolated false positives) are naturally excluded.

Confidence scoring uses three components:
1. Event count (log scale, saturates at ~20 independent reports)
2. Type consistency (fraction with the dominant anomaly type)
3. Recency (decay over 90 days)

A single report from one device never exceeds confidence ~0.3. Two or more
independent reports at the same location push confidence above 0.5, at which
point the cluster appears on the map by default.

---

## Data Flow: End to End

```
Drive starts
  GPS speed: 22 km/h
  -> Speed gate opens
  -> Accelerometer + Gyroscope registered at 50Hz

At 00:00.000:
  Z-axis: 9.81 (baseline)

At 00:00.150:
  Z-axis drops to 7.2 (dip, wheel enters pothole)
  Gyro magnitude: 0.92 rad/s (pitch confirmed)

At 00:00.210:
  Z-axis spikes to 15.8 (rebound)
  Delta from baseline: 6.0 m/s^2 (exceeds 4.0 threshold)
  Gyro still active

  -> AnomalyDetector fires
  -> Signature: dip before spike -> POTHOLE
  -> Intensity: (6.0 / 12.0) * 1.1 (speed factor) = 0.55
  -> GPS fix: lat=37.7749, lon=-122.4194, accuracy=8m
  -> Event stored to Room DB

...more driving...

After 5 minutes OR buffer fills to 200:
  WorkManager fires UploadWorker
  -> 12 events batched
  -> POST /v1/ingest/batch with batch_id=UUID
  -> Server validates, deduplicates, stores to PostgreSQL
  -> Events marked as uploaded in Room DB

At T+5 minutes (backend side):
  Clustering worker runs DBSCAN
  -> 3 events within 30m -> new cluster at centroid
  -> Confidence: 0.42 (3 events, consistent type, recent)
  -> Cluster written to anomaly_clusters table

Map client query:
  GET /v1/map/clusters?min_lat=37.7&min_lon=-122.45&max_lat=37.8&max_lon=-122.4
  -> Returns cluster with confidence=0.42, intensity=0.55
  -> Map renders amber marker at lat=37.7749, lon=-122.4194
```

---

## Deployment Topology

```
                         +------------------+
                         |   Load Balancer  |
                         +--------+---------+
                                  |
               +------------------+-------------------+
               |                                      |
     +---------+--------+                  +----------+--------+
     |   API Server 1   |                  |   API Server 2   |
     |   (Go binary)    |                  |   (Go binary)    |
     +---------+--------+                  +----------+--------+
               |                                      |
               +------------------+-------------------+
                                  |
                         +--------+---------+
                         |   PostgreSQL     |
                         |   + PostGIS      |
                         |   (Primary)      |
                         +------------------+
```

For a single-operator deployment, a single VM running docker-compose is
sufficient for up to approximately 10,000 active users. The main bottleneck
is PostgreSQL write throughput, which can be addressed with connection pooling
(PgBouncer) and read replicas for the query endpoints.
