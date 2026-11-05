package storage

import (
	"context"
	"path/filepath"
	"testing"

	"github.com/asphalt-maps/asphalt/backend/internal/model"
)

// openSQLite returns a migrated database in a fresh temporary file.
func openSQLite(t *testing.T) *DB {
	t.Helper()
	db, err := New("sqlite:" + filepath.Join(t.TempDir(), "asphalt.db"))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	t.Cleanup(func() { db.db.Close() })
	if err := db.Migrate(context.Background()); err != nil {
		t.Fatalf("Migrate: %v", err)
	}
	return db
}

func TestNewRejectsUnknownScheme(t *testing.T) {
	if _, err := New("mysql://localhost/asphalt"); err == nil {
		t.Fatal("expected an error for an unsupported scheme")
	}
}

func TestMigrateIsIdempotent(t *testing.T) {
	db := openSQLite(t)
	if err := db.Migrate(context.Background()); err != nil {
		t.Fatalf("second Migrate: %v", err)
	}
	if db.Driver() != "sqlite" {
		t.Fatalf("Driver() = %q, want sqlite", db.Driver())
	}
}

// TestSQLiteRoundTrip runs every storage operation the server uses, in the
// order the ingestion and clustering paths call them.
func TestSQLiteRoundTrip(t *testing.T) {
	ctx := context.Background()
	db := openSQLite(t)

	// Batches are idempotent.
	if ok, err := db.BatchExists(ctx, "b1"); err != nil || ok {
		t.Fatalf("BatchExists before record = %v, %v", ok, err)
	}
	if err := db.RecordBatch(ctx, "b1", 2); err != nil {
		t.Fatalf("RecordBatch: %v", err)
	}
	if err := db.RecordBatch(ctx, "b1", 2); err != nil {
		t.Fatalf("RecordBatch duplicate: %v", err)
	}
	if ok, err := db.BatchExists(ctx, "b1"); err != nil || !ok {
		t.Fatalf("BatchExists after record = %v, %v", ok, err)
	}

	// Events insert, ignore duplicates and default the vehicle type.
	events := []model.Event{
		{EventID: "e1", TimestampMs: 1000, Latitude: 40.6796, Longitude: -73.9531, Intensity: 0.6, AnomalyType: model.AnomalyTypePothole, VehicleType: model.VehicleTypeTwoWheeler},
		{EventID: "e2", TimestampMs: 2000, Latitude: 40.6797, Longitude: -73.9532, Intensity: 0.7, AnomalyType: model.AnomalyTypePothole},
	}
	if err := db.InsertEvents(ctx, events); err != nil {
		t.Fatalf("InsertEvents: %v", err)
	}
	if err := db.InsertEvents(ctx, events[:1]); err != nil {
		t.Fatalf("InsertEvents duplicate: %v", err)
	}

	pending, err := db.GetUnclusteredEvents(ctx, 10)
	if err != nil {
		t.Fatalf("GetUnclusteredEvents: %v", err)
	}
	if len(pending) != 2 || pending[0].EventID != "e1" || pending[1].VehicleType != model.VehicleTypeFourWheeler {
		t.Fatalf("unexpected unclustered events: %+v", pending)
	}

	// Clusters upsert and read back with their vehicle counts.
	c := model.AnomalyCluster{
		ClusterID: "c1", Latitude: 40.6796, Longitude: -73.9531, AnomalyType: model.AnomalyTypePothole,
		Confidence: 0.6, EventCount: 2, AvgIntensity: 0.65, LastSeenMs: 2000, RadiusM: 5,
		VehicleTypeCounts:    map[string]int{model.VehicleTypeFourWheeler: 1, model.VehicleTypeTwoWheeler: 1},
		VehicleTypeDiversity: 2,
	}
	if err := db.UpsertCluster(ctx, c); err != nil {
		t.Fatalf("UpsertCluster: %v", err)
	}
	c.Confidence = 0.7
	if err := db.UpsertCluster(ctx, c); err != nil {
		t.Fatalf("UpsertCluster update: %v", err)
	}
	if err := db.MarkEventsClustered(ctx, []string{"e1", "e2"}, "c1"); err != nil {
		t.Fatalf("MarkEventsClustered: %v", err)
	}
	if pending, _ := db.GetUnclusteredEvents(ctx, 10); len(pending) != 0 {
		t.Fatalf("events still unclustered: %+v", pending)
	}

	got, err := db.GetClusters(ctx, 40, -74, 41, -73, 10)
	if err != nil {
		t.Fatalf("GetClusters: %v", err)
	}
	if len(got) != 1 || got[0].Confidence < 0.69 || got[0].VehicleTypeCounts[model.VehicleTypeTwoWheeler] != 1 {
		t.Fatalf("unexpected clusters: %+v", got)
	}
	if tile, err := db.GetClustersForTile(ctx, 40, -74, 41, -73, 0.8); err != nil || len(tile) != 0 {
		t.Fatalf("GetClustersForTile above confidence = %+v, %v", tile, err)
	}

	stats, err := db.GetDashboardStats(ctx)
	if err != nil {
		t.Fatalf("GetDashboardStats: %v", err)
	}
	if stats.TotalEvents != 2 || stats.TotalClusters != 1 || stats.Events24h != 2 {
		t.Fatalf("unexpected stats: %+v", stats)
	}
}
