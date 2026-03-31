# Backend Setup

## Why Go

The backend is written in Go for the following reasons:

1. **Goroutine-based concurrency**: Batch ingestion from thousands of
   simultaneous devices is handled efficiently with one goroutine per request.
   Go's runtime schedules these across available CPU cores with minimal overhead.
   A Node.js single-threaded event loop would require worker threads to handle
   CPU-bound clustering; Go handles this naturally.

2. **Low latency under load**: Go's garbage collector is tuned for short pause
   times. The 99th-percentile ingestion latency is predictable even at high
   request rates. This matters because SDK clients use a fixed read timeout
   (20 seconds); GC pauses longer than that would cause false timeout retries.

3. **Minimal deployment surface**: The build output is a single static binary
   with no runtime dependencies. The production Docker image is ~12MB total.
   No Node version management, no `node_modules`, no JVM heap tuning.

4. **Strong typing**: The data pipeline benefits from compile-time type safety.
   Sensor data passes through multiple validation, transformation, and storage
   stages; Go's type system catches category errors at build time.

---

## Prerequisites

- Docker and Docker Compose v2
- Go 1.22+ (for local development without Docker)

---

## Quick Start with Docker Compose

```bash
cd asphalt/backend
docker compose up --build
```

This starts:
- PostgreSQL 16 with PostGIS extension on port 5432
- Asphalt API server on port 8080

Verify the server is healthy:
```bash
curl http://localhost:8080/v1/health
# {"status":"ok"}
```

---

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DATABASE_URL` | `postgres://asphalt:asphalt@localhost:5432/asphalt?sslmode=disable` | PostgreSQL DSN |
| `LISTEN_ADDR` | `:8080` | HTTP listen address |
| `CLUSTER_INTERVAL` | `5m` | How often the clustering worker runs |

---

## Running Locally (without Docker)

**1. Start PostgreSQL with PostGIS:**
```bash
# Using Docker for just the database
docker run -d \
  -e POSTGRES_USER=asphalt \
  -e POSTGRES_PASSWORD=asphalt \
  -e POSTGRES_DB=asphalt \
  -p 5432:5432 \
  postgis/postgis:16-3.4-alpine
```

**2. Run the server:**
```bash
cd asphalt/backend
go run ./cmd/server
```

The server runs migrations automatically on startup.

---

## API Endpoints

### POST /v1/ingest/batch

Accepts a JSON event batch from the SDK.

```bash
curl -X POST http://localhost:8080/v1/ingest/batch \
  -H "Content-Type: application/json" \
  -d '{
    "batch_id": "550e8400-e29b-41d4-a716-446655440000",
    "submitted_at_ms": 1711756800000,
    "events": [{
      "event_id": "7c3e6b2a-1234-4567-89ab-cdef01234567",
      "timestamp_ms": 1711756795000,
      "latitude": 37.7749,
      "longitude": -122.4194,
      "accuracy_m": 8.0,
      "intensity": 0.65,
      "speed_kmh": 48.5,
      "anomaly_type": "pothole",
      "vehicle_type": "four_wheeler",
      "sensor_summary": {
        "accel_peak_z": 15.2,
        "accel_baseline_z": 9.81,
        "accel_delta_z": 5.39,
        "gyro_peak_magnitude": 0.85,
        "sample_count": 24,
        "window_duration_ms": 500
      },
      "device_meta": {
        "platform": "android",
        "sdk_int": 33,
        "manufacturer": "Google",
        "model": "Pixel 7",
        "sensor_vendor": "InvenSense"
      },
      "sdk_version": "1.0.0",
      "session_id": "a3f7c1b2-0000-0000-0000-000000000001"
    }]
  }'
```

Response:
```json
{"batch_id":"550e8400-e29b-41d4-a716-446655440000","accepted_count":1,"duplicate":false}
```

### GET /v1/map/clusters

Query clusters within a bounding box:

```bash
curl "http://localhost:8080/v1/map/clusters?min_lat=37.77&min_lon=-122.43&max_lat=37.78&max_lon=-122.41"
```

### GET /v1/map/tiles/{z}/{x}/{y}

Slippy map tile query (zoom 0-18):

```bash
curl "http://localhost:8080/v1/map/tiles/14/2620/6338"
```

---

## Database Schema

The full schema is in `internal/storage/queries.go`. Key tables:

**road_events**: Raw events as received. Never modified after insert. Has a
PostGIS `geom` generated column for spatial queries.

**anomaly_clusters**: Aggregated clusters produced by the clustering worker.
Updated incrementally (UPSERT) each run.

**ingested_batches**: Deduplication log. Prevents double-processing of retried
batch uploads.

---

## Production Considerations

**Connection pooling**: The server uses `database/sql` with 25 max open
connections. For high traffic, add PgBouncer in transaction mode between the
server and PostgreSQL.

**Read replicas**: The query endpoints (`/v1/map/*`) are read-only. Routing
them to a PostgreSQL read replica reduces load on the primary.

**TLS**: The server does not terminate TLS. Run behind a reverse proxy (nginx,
Caddy, or a cloud load balancer) that handles TLS termination.

**Rate limiting**: The middleware chain includes a placeholder `RateLimitMiddleware`.
For production, implement a sliding window counter backed by Redis or use a
dedicated rate-limiting proxy layer.

**Observability**: Add structured logging (e.g. `log/slog`) and expose a
Prometheus `/metrics` endpoint for request counts, ingestion latency, and
clustering cycle duration.
