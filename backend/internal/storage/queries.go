package storage

// schema contains the DDL for the initial database schema.
// Events are partitioned by ingested_at for efficient archival.
// PostGIS is used for spatial indexing of event coordinates.
const schema = `
-- Enable PostGIS for spatial operations
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Raw road anomaly events as received from SDK batches
CREATE TABLE IF NOT EXISTS road_events (
    event_id        TEXT PRIMARY KEY,
    timestamp_ms    BIGINT NOT NULL,
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    accuracy_m      REAL,
    intensity       REAL NOT NULL,
    speed_kmh       REAL,
    anomaly_type    TEXT NOT NULL,
    -- Sensor summary (flattened for query simplicity)
    accel_peak_z        REAL,
    accel_baseline_z    REAL,
    accel_delta_z       REAL,
    gyro_peak_magnitude REAL,
    sample_count        INTEGER,
    window_duration_ms  BIGINT,
    -- Device meta
    platform        TEXT,
    sdk_int         INTEGER,
    manufacturer    TEXT,
    model           TEXT,
    sensor_vendor   TEXT,
    sdk_version     TEXT,
    session_id      TEXT,
    vehicle_type    TEXT NOT NULL DEFAULT 'four_wheeler',
    -- Clustering state
    cluster_id      TEXT,
    -- Ingestion metadata
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- PostGIS geometry column for spatial indexing
    geom            GEOMETRY(Point, 4326)
        GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)) STORED
);

-- Spatial index on event geometry
CREATE INDEX IF NOT EXISTS idx_road_events_geom
    ON road_events USING GIST (geom);

CREATE INDEX IF NOT EXISTS idx_road_events_timestamp
    ON road_events (timestamp_ms DESC);

CREATE INDEX IF NOT EXISTS idx_road_events_cluster_id
    ON road_events (cluster_id)
    WHERE cluster_id IS NULL;  -- Partial index for unclustered events

CREATE INDEX IF NOT EXISTS idx_road_events_vehicle_type
    ON road_events (vehicle_type);

-- Aggregated cluster table
CREATE TABLE IF NOT EXISTS anomaly_clusters (
    cluster_id      TEXT PRIMARY KEY,
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    anomaly_type    TEXT NOT NULL,
    confidence      REAL NOT NULL,
    event_count     INTEGER NOT NULL DEFAULT 0,
    avg_intensity   REAL NOT NULL,
    last_seen_ms    BIGINT NOT NULL,
    radius_m             DOUBLE PRECISION NOT NULL,
    vehicle_type_counts  JSONB NOT NULL DEFAULT '{}',
    vehicle_diversity    INTEGER NOT NULL DEFAULT 1,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    geom            GEOMETRY(Point, 4326)
        GENERATED ALWAYS AS (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)) STORED
);

CREATE INDEX IF NOT EXISTS idx_clusters_geom
    ON anomaly_clusters USING GIST (geom);

CREATE INDEX IF NOT EXISTS idx_clusters_confidence
    ON anomaly_clusters (confidence DESC);

-- Batch deduplication table
CREATE TABLE IF NOT EXISTS ingested_batches (
    batch_id        TEXT PRIMARY KEY,
    event_count     INTEGER NOT NULL,
    ingested_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
`

const insertEventSQL = `
INSERT INTO road_events (
    event_id, timestamp_ms, latitude, longitude, accuracy_m,
    intensity, speed_kmh, anomaly_type, vehicle_type,
    accel_peak_z, accel_baseline_z, accel_delta_z, gyro_peak_magnitude,
    sample_count, window_duration_ms,
    platform, sdk_int, manufacturer, model, sensor_vendor,
    sdk_version, session_id
) VALUES (
    $1, $2, $3, $4, $5,
    $6, $7, $8, $9,
    $10, $11, $12, $13,
    $14, $15,
    $16, $17, $18, $19, $20,
    $21, $22
) ON CONFLICT (event_id) DO NOTHING
`

const getClustersSQL = `
SELECT cluster_id, latitude, longitude, anomaly_type,
       confidence, event_count, avg_intensity, last_seen_ms, radius_m,
       vehicle_type_counts, vehicle_diversity
FROM anomaly_clusters
WHERE latitude BETWEEN $1 AND $2
  AND longitude BETWEEN $3 AND $4
ORDER BY confidence DESC
LIMIT $5
`

const getTileSQL = `
SELECT cluster_id, latitude, longitude, anomaly_type,
       confidence, event_count, avg_intensity, last_seen_ms, radius_m,
       vehicle_type_counts, vehicle_diversity
FROM anomaly_clusters
WHERE latitude BETWEEN $1 AND $2
  AND longitude BETWEEN $3 AND $4
  AND confidence >= $5
ORDER BY confidence DESC
`

const upsertClusterSQL = `
INSERT INTO anomaly_clusters (
    cluster_id, latitude, longitude, anomaly_type,
    confidence, event_count, avg_intensity, last_seen_ms, radius_m,
    vehicle_type_counts, vehicle_diversity
) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11)
ON CONFLICT (cluster_id) DO UPDATE SET
    latitude             = EXCLUDED.latitude,
    longitude            = EXCLUDED.longitude,
    confidence           = EXCLUDED.confidence,
    event_count          = EXCLUDED.event_count,
    avg_intensity        = EXCLUDED.avg_intensity,
    last_seen_ms         = EXCLUDED.last_seen_ms,
    radius_m             = EXCLUDED.radius_m,
    vehicle_type_counts  = EXCLUDED.vehicle_type_counts,
    vehicle_diversity    = EXCLUDED.vehicle_diversity,
    updated_at           = NOW()
`

const getUnclusteredSQL = `
SELECT event_id, timestamp_ms, latitude, longitude, intensity, anomaly_type, vehicle_type
FROM road_events
WHERE cluster_id IS NULL
ORDER BY timestamp_ms ASC
LIMIT $1
`
