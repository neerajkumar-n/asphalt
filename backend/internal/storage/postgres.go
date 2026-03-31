package storage

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	_ "github.com/lib/pq"

	"github.com/asphalt-maps/asphalt/backend/internal/model"
)

// DB wraps a sql.DB connection pool and provides all storage operations.
type DB struct {
	db *sql.DB
}

// New opens a connection pool to PostgreSQL and verifies connectivity.
func New(dsn string) (*DB, error) {
	db, err := sql.Open("postgres", dsn)
	if err != nil {
		return nil, fmt.Errorf("open db: %w", err)
	}

	db.SetMaxOpenConns(25)
	db.SetMaxIdleConns(5)
	db.SetConnMaxLifetime(5 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := db.PingContext(ctx); err != nil {
		return nil, fmt.Errorf("ping db: %w", err)
	}

	return &DB{db: db}, nil
}

// Migrate runs the SQL schema migrations.
// In production, use a proper migration tool (golang-migrate, Atlas).
// This is provided for development convenience.
func (d *DB) Migrate(ctx context.Context) error {
	_, err := d.db.ExecContext(ctx, schema)
	return err
}

// InsertEvents bulk-inserts a slice of events.
// Uses a single multi-value INSERT for efficiency.
// Duplicates (same event_id) are silently ignored via ON CONFLICT DO NOTHING.
func (d *DB) InsertEvents(ctx context.Context, events []model.Event) error {
	if len(events) == 0 {
		return nil
	}

	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	stmt, err := tx.PrepareContext(ctx, insertEventSQL)
	if err != nil {
		return fmt.Errorf("prepare: %w", err)
	}
	defer stmt.Close()

	for i := range events {
		e := &events[i]
		vehicleType := e.VehicleType
		if vehicleType == "" {
			vehicleType = model.VehicleTypeFourWheeler
		}
		_, err := stmt.ExecContext(ctx,
			e.EventID,
			e.TimestampMs,
			e.Latitude,
			e.Longitude,
			e.AccuracyM,
			e.Intensity,
			e.SpeedKmh,
			e.AnomalyType,
			vehicleType,
			e.SensorSummary.AccelPeakZ,
			e.SensorSummary.AccelBaselineZ,
			e.SensorSummary.AccelDeltaZ,
			e.SensorSummary.GyroPeakMagnitude,
			e.SensorSummary.SampleCount,
			e.SensorSummary.WindowDurationMs,
			e.DeviceMeta.Platform,
			e.DeviceMeta.SDKInt,
			e.DeviceMeta.Manufacturer,
			e.DeviceMeta.Model,
			e.DeviceMeta.SensorVendor,
			e.SDKVersion,
			e.SessionID,
		)
		if err != nil {
			return fmt.Errorf("insert event %s: %w", e.EventID, err)
		}
	}

	return tx.Commit()
}

// BatchExists returns true if a batch_id has already been processed.
// Used for idempotent ingestion.
func (d *DB) BatchExists(ctx context.Context, batchID string) (bool, error) {
	var exists bool
	err := d.db.QueryRowContext(ctx,
		`SELECT EXISTS(SELECT 1 FROM ingested_batches WHERE batch_id = $1)`,
		batchID,
	).Scan(&exists)
	return exists, err
}

// RecordBatch marks a batch as processed.
func (d *DB) RecordBatch(ctx context.Context, batchID string, eventCount int) error {
	_, err := d.db.ExecContext(ctx,
		`INSERT INTO ingested_batches (batch_id, event_count, ingested_at)
		 VALUES ($1, $2, NOW())
		 ON CONFLICT (batch_id) DO NOTHING`,
		batchID, eventCount,
	)
	return err
}

// GetClusters returns clusters within a bounding box.
func (d *DB) GetClusters(ctx context.Context, minLat, minLon, maxLat, maxLon float64, limit int) ([]model.AnomalyCluster, error) {
	rows, err := d.db.QueryContext(ctx, getClustersSQL, minLat, maxLat, minLon, maxLon, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var clusters []model.AnomalyCluster
	for rows.Next() {
		c, err := scanCluster(rows)
		if err != nil {
			return nil, err
		}
		clusters = append(clusters, c)
	}
	return clusters, rows.Err()
}

// GetClustersForTile returns clusters within the lat/lon bounds of a map tile.
func (d *DB) GetClustersForTile(ctx context.Context, minLat, minLon, maxLat, maxLon float64, minConfidence float32) ([]model.AnomalyCluster, error) {
	rows, err := d.db.QueryContext(ctx, getTileSQL, minLat, maxLat, minLon, maxLon, minConfidence)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var clusters []model.AnomalyCluster
	for rows.Next() {
		c, err := scanCluster(rows)
		if err != nil {
			return nil, err
		}
		clusters = append(clusters, c)
	}
	return clusters, rows.Err()
}

// scanCluster scans one row from the clusters SELECT queries.
func scanCluster(rows *sql.Rows) (model.AnomalyCluster, error) {
	var c model.AnomalyCluster
	var vtCountsJSON []byte
	err := rows.Scan(
		&c.ClusterID,
		&c.Latitude,
		&c.Longitude,
		&c.AnomalyType,
		&c.Confidence,
		&c.EventCount,
		&c.AvgIntensity,
		&c.LastSeenMs,
		&c.RadiusM,
		&vtCountsJSON,
		&c.VehicleTypeDiversity,
	)
	if err != nil {
		return c, err
	}
	if len(vtCountsJSON) > 0 {
		// Parse the JSONB vehicle_type_counts column
		_ = json.Unmarshal(vtCountsJSON, &c.VehicleTypeCounts)
	}
	return c, nil
}

// UpsertCluster inserts or updates a cluster record.
func (d *DB) UpsertCluster(ctx context.Context, c model.AnomalyCluster) error {
	vtJSON, err := json.Marshal(c.VehicleTypeCounts)
	if err != nil {
		vtJSON = []byte("{}")
	}
	_, err = d.db.ExecContext(ctx, upsertClusterSQL,
		c.ClusterID,
		c.Latitude,
		c.Longitude,
		c.AnomalyType,
		c.Confidence,
		c.EventCount,
		c.AvgIntensity,
		c.LastSeenMs,
		c.RadiusM,
		vtJSON,
		c.VehicleTypeDiversity,
	)
	return err
}

// GetUnclusteredEvents returns raw events not yet assigned to a cluster.
func (d *DB) GetUnclusteredEvents(ctx context.Context, limit int) ([]model.Event, error) {
	rows, err := d.db.QueryContext(ctx, getUnclusteredSQL, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var events []model.Event
	for rows.Next() {
		var e model.Event
		if err := rows.Scan(
			&e.EventID,
			&e.TimestampMs,
			&e.Latitude,
			&e.Longitude,
			&e.Intensity,
			&e.AnomalyType,
			&e.VehicleType,
		); err != nil {
			return nil, err
		}
		events = append(events, e)
	}
	return events, rows.Err()
}

// MarkEventsClustered marks a set of event IDs as clustered.
func (d *DB) MarkEventsClustered(ctx context.Context, eventIDs []string, clusterID string) error {
	// Use ANY for bulk update
	_, err := d.db.ExecContext(ctx,
		`UPDATE road_events SET cluster_id = $1 WHERE event_id = ANY($2)`,
		clusterID, eventIDs,
	)
	return err
}
