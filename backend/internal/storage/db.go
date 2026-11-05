package storage

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	_ "github.com/lib/pq"  // PostgreSQL driver
	_ "modernc.org/sqlite" // SQLite driver (pure Go, no cgo)

	"github.com/asphalt-maps/asphalt/backend/internal/model"
)

type dialect int

const (
	dialectPostgres dialect = iota
	dialectSQLite
)

// DB wraps a sql.DB connection pool and provides all storage operations.
type DB struct {
	db      *sql.DB
	dialect dialect
}

// New opens the database named by url and verifies connectivity.
//
//	sqlite:<path>                   SQLite file, created if missing (the default)
//	postgres://user:pass@host/db    PostgreSQL
//
// The schema uses no extensions, so any PostgreSQL 13+ works.
func New(url string) (*DB, error) {
	var (
		db  *sql.DB
		d   dialect
		err error
	)
	switch {
	case strings.HasPrefix(url, "postgres://"), strings.HasPrefix(url, "postgresql://"):
		d = dialectPostgres
		db, err = sql.Open("postgres", url)
		if err == nil {
			db.SetMaxOpenConns(25)
			db.SetMaxIdleConns(5)
			db.SetConnMaxLifetime(5 * time.Minute)
		}
	case strings.HasPrefix(url, "sqlite:"):
		d = dialectSQLite
		path := strings.TrimPrefix(url, "sqlite:")
		db, err = sql.Open("sqlite", "file:"+path+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)")
		if err == nil {
			// SQLite allows one writer at a time; a single connection avoids
			// "database is locked" errors between ingestion and clustering.
			db.SetMaxOpenConns(1)
		}
	default:
		return nil, fmt.Errorf("unsupported DATABASE_URL %q: use sqlite:<path> or postgres://...", url)
	}
	if err != nil {
		return nil, fmt.Errorf("open db: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := db.PingContext(ctx); err != nil {
		return nil, fmt.Errorf("ping db: %w", err)
	}

	return &DB{db: db, dialect: d}, nil
}

// Driver names the database in use, for logging.
func (d *DB) Driver() string {
	if d.dialect == dialectSQLite {
		return "sqlite"
	}
	return "postgres"
}

// Migrate runs the SQL schema migrations.
// In production, use a proper migration tool (golang-migrate, Atlas).
// This is provided for development convenience.
func (d *DB) Migrate(ctx context.Context) error {
	ddl := schema
	if d.dialect == dialectSQLite {
		ddl = strings.ReplaceAll(ddl, "JSONB", "TEXT")
	}
	_, err := d.db.ExecContext(ctx, ddl)
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
		 VALUES ($1, $2, CURRENT_TIMESTAMP)
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

// GetDashboardStats returns aggregate counts for the dashboard overview cards.
func (d *DB) GetDashboardStats(ctx context.Context) (model.DashboardStats, error) {
	dayAgo := "NOW() - INTERVAL '24 hours'"
	if d.dialect == dialectSQLite {
		dayAgo = "datetime('now', '-1 day')"
	}
	var s model.DashboardStats
	err := d.db.QueryRowContext(ctx, `
		SELECT
			(SELECT COUNT(*) FROM road_events)                              AS total_events,
			(SELECT COUNT(*) FROM anomaly_clusters)                         AS total_clusters,
			(SELECT COUNT(*) FROM road_events WHERE ingested_at > `+dayAgo+`) AS events_24h,
			(SELECT COALESCE(AVG(confidence), 0) FROM anomaly_clusters)     AS avg_confidence
	`).Scan(&s.TotalEvents, &s.TotalClusters, &s.Events24h, &s.AvgConfidence)
	return s, err
}

// MarkEventsClustered marks a set of event IDs as clustered.
func (d *DB) MarkEventsClustered(ctx context.Context, eventIDs []string, clusterID string) error {
	tx, err := d.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin tx: %w", err)
	}
	defer tx.Rollback()

	stmt, err := tx.PrepareContext(ctx, `UPDATE road_events SET cluster_id = $1 WHERE event_id = $2`)
	if err != nil {
		return fmt.Errorf("prepare: %w", err)
	}
	defer stmt.Close()

	for _, id := range eventIDs {
		if _, err := stmt.ExecContext(ctx, clusterID, id); err != nil {
			return fmt.Errorf("mark event %s: %w", id, err)
		}
	}
	return tx.Commit()
}
