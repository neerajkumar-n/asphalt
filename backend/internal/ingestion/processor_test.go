package ingestion

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/asphalt-maps/asphalt/backend/internal/model"
)

// fakeStore implements Store for testing without a real database.
type fakeStore struct {
	existingBatches map[string]bool
	insertedEvents  []model.Event
	recordedBatches []string

	batchExistsErr error
	insertErr      error
	recordErr      error
}

func newFakeStore() *fakeStore {
	return &fakeStore{existingBatches: make(map[string]bool)}
}

func (f *fakeStore) BatchExists(_ context.Context, batchID string) (bool, error) {
	if f.batchExistsErr != nil {
		return false, f.batchExistsErr
	}
	return f.existingBatches[batchID], nil
}

func (f *fakeStore) InsertEvents(_ context.Context, events []model.Event) error {
	if f.insertErr != nil {
		return f.insertErr
	}
	f.insertedEvents = append(f.insertedEvents, events...)
	return nil
}

func (f *fakeStore) RecordBatch(_ context.Context, batchID string, _ int) error {
	if f.recordErr != nil {
		return f.recordErr
	}
	f.recordedBatches = append(f.recordedBatches, batchID)
	return nil
}

// validEvent returns an Event that passes Validate() for use in batch tests.
func validEvent(id string) model.Event {
	return model.Event{
		EventID:     id,
		TimestampMs: time.Now().UnixMilli(),
		Latitude:    12.97,
		Longitude:   77.59,
		AccuracyM:   8.0,
		Intensity:   0.65,
		SpeedKmh:    35.0,
		AnomalyType: model.AnomalyTypePothole,
		VehicleType: model.VehicleTypeFourWheeler,
	}
}

// -------------------------------------------------------------------------
// Batch-level validation (no DB access needed)
// -------------------------------------------------------------------------

func TestProcessBatch_missingBatchID_returnsError(t *testing.T) {
	p := &Processor{db: newFakeStore()}
	batch := model.EventBatch{BatchID: "", Events: []model.Event{validEvent("e1")}}

	_, _, err := p.ProcessBatch(context.Background(), batch)
	if err == nil || !strings.Contains(err.Error(), "batch_id") {
		t.Errorf("expected batch_id error, got: %v", err)
	}
}

func TestProcessBatch_emptyEvents_returnsError(t *testing.T) {
	p := &Processor{db: newFakeStore()}
	batch := model.EventBatch{BatchID: "b1", Events: []model.Event{}}

	_, _, err := p.ProcessBatch(context.Background(), batch)
	if err == nil || !strings.Contains(err.Error(), "empty") {
		t.Errorf("expected empty events error, got: %v", err)
	}
}

func TestProcessBatch_tooManyEvents_returnsError(t *testing.T) {
	p := &Processor{db: newFakeStore()}
	events := make([]model.Event, model.MaxEventsPerBatch+1)
	for i := range events {
		events[i] = validEvent("e-overflow")
	}
	batch := model.EventBatch{BatchID: "b1", Events: events}

	_, _, err := p.ProcessBatch(context.Background(), batch)
	if err == nil || !strings.Contains(err.Error(), "maximum") {
		t.Errorf("expected max-size error, got: %v", err)
	}
}

// -------------------------------------------------------------------------
// Duplicate batch
// -------------------------------------------------------------------------

func TestProcessBatch_duplicateBatch_returnsDuplicateTrue(t *testing.T) {
	store := newFakeStore()
	store.existingBatches["batch-dup"] = true
	p := &Processor{db: store}

	batch := model.EventBatch{BatchID: "batch-dup", Events: []model.Event{validEvent("e1")}}
	accepted, duplicate, err := p.ProcessBatch(context.Background(), batch)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !duplicate {
		t.Error("expected duplicate=true for already-seen batch")
	}
	if len(store.insertedEvents) != 0 {
		t.Error("duplicate batch must not re-insert events")
	}
	_ = accepted
}

func TestProcessBatch_batchExistsError_propagates(t *testing.T) {
	store := newFakeStore()
	store.batchExistsErr = errors.New("db unavailable")
	p := &Processor{db: store}

	batch := model.EventBatch{BatchID: "b1", Events: []model.Event{validEvent("e1")}}
	_, _, err := p.ProcessBatch(context.Background(), batch)
	if err == nil {
		t.Error("expected error when BatchExists fails")
	}
}

// -------------------------------------------------------------------------
// Happy path
// -------------------------------------------------------------------------

func TestProcessBatch_validBatch_insertsEvents(t *testing.T) {
	store := newFakeStore()
	p := &Processor{db: store}

	batch := model.EventBatch{
		BatchID: "batch-ok",
		Events:  []model.Event{validEvent("e1"), validEvent("e2")},
	}
	accepted, duplicate, err := p.ProcessBatch(context.Background(), batch)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if duplicate {
		t.Error("expected duplicate=false for new batch")
	}
	if accepted != 2 {
		t.Errorf("expected 2 accepted, got %d", accepted)
	}
	if len(store.insertedEvents) != 2 {
		t.Errorf("expected 2 inserted events, got %d", len(store.insertedEvents))
	}
	if len(store.recordedBatches) != 1 || store.recordedBatches[0] != "batch-ok" {
		t.Errorf("expected batch-ok to be recorded, got %v", store.recordedBatches)
	}
}

// -------------------------------------------------------------------------
// Timestamp filtering
// -------------------------------------------------------------------------

func TestProcessBatch_futureTimestamp_eventRejected(t *testing.T) {
	store := newFakeStore()
	p := &Processor{db: store}

	future := validEvent("e-future")
	future.TimestampMs = time.Now().Add(10 * time.Minute).UnixMilli()

	batch := model.EventBatch{BatchID: "b-future", Events: []model.Event{future}}
	accepted, _, err := p.ProcessBatch(context.Background(), batch)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if accepted != 0 {
		t.Errorf("expected 0 accepted for future timestamp, got %d", accepted)
	}
	if len(store.insertedEvents) != 0 {
		t.Error("future-timestamp event must not be inserted")
	}
}

func TestProcessBatch_oldTimestamp_eventRejected(t *testing.T) {
	store := newFakeStore()
	p := &Processor{db: store}

	old := validEvent("e-old")
	old.TimestampMs = time.Now().Add(-8 * 24 * time.Hour).UnixMilli()

	batch := model.EventBatch{BatchID: "b-old", Events: []model.Event{old}}
	accepted, _, err := p.ProcessBatch(context.Background(), batch)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if accepted != 0 {
		t.Errorf("expected 0 accepted for 8-day-old event, got %d", accepted)
	}
}

func TestProcessBatch_mixedTimestamps_onlyValidAccepted(t *testing.T) {
	store := newFakeStore()
	p := &Processor{db: store}

	valid := validEvent("e-valid")
	future := validEvent("e-future")
	future.TimestampMs = time.Now().Add(10 * time.Minute).UnixMilli()

	batch := model.EventBatch{
		BatchID: "b-mixed",
		Events:  []model.Event{valid, future},
	}
	accepted, _, err := p.ProcessBatch(context.Background(), batch)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if accepted != 1 {
		t.Errorf("expected 1 accepted, got %d", accepted)
	}
}

// -------------------------------------------------------------------------
// DB error paths
// -------------------------------------------------------------------------

func TestProcessBatch_insertError_propagates(t *testing.T) {
	store := newFakeStore()
	store.insertErr = errors.New("constraint violation")
	p := &Processor{db: store}

	batch := model.EventBatch{BatchID: "b1", Events: []model.Event{validEvent("e1")}}
	_, _, err := p.ProcessBatch(context.Background(), batch)
	if err == nil {
		t.Error("expected error when InsertEvents fails")
	}
}

func TestProcessBatch_recordBatchError_isNonFatal(t *testing.T) {
	store := newFakeStore()
	store.recordErr = errors.New("record failed")
	p := &Processor{db: store}

	// RecordBatch failure is explicitly non-fatal — processing must still succeed.
	batch := model.EventBatch{BatchID: "b1", Events: []model.Event{validEvent("e1")}}
	accepted, _, err := p.ProcessBatch(context.Background(), batch)
	if err != nil {
		t.Errorf("RecordBatch failure must be non-fatal, got error: %v", err)
	}
	if accepted != 1 {
		t.Errorf("expected 1 accepted, got %d", accepted)
	}
}
