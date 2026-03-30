package ingestion

import (
	"context"
	"fmt"
	"log"
	"time"

	"github.com/asphalt-maps/asphalt/backend/internal/model"
	"github.com/asphalt-maps/asphalt/backend/internal/storage"
)

// Processor handles the ingestion pipeline for incoming event batches.
// It validates events, checks for duplicates, and stores valid events.
type Processor struct {
	db *storage.DB
}

func New(db *storage.DB) *Processor {
	return &Processor{db: db}
}

// ProcessBatch validates and stores a batch of events.
// Returns the number of accepted events and whether the batch was a duplicate.
func (p *Processor) ProcessBatch(ctx context.Context, batch model.EventBatch) (int, bool, error) {
	if batch.BatchID == "" {
		return 0, false, fmt.Errorf("batch_id is required")
	}
	if len(batch.Events) == 0 {
		return 0, false, fmt.Errorf("events array is empty")
	}
	if len(batch.Events) > model.MaxEventsPerBatch {
		return 0, false, fmt.Errorf("batch exceeds maximum of %d events", model.MaxEventsPerBatch)
	}

	// Check for duplicate batch
	exists, err := p.db.BatchExists(ctx, batch.BatchID)
	if err != nil {
		return 0, false, fmt.Errorf("batch dedup check: %w", err)
	}
	if exists {
		log.Printf("ingestion: duplicate batch %s, skipping", batch.BatchID)
		return len(batch.Events), true, nil
	}

	// Validate and filter events
	valid := make([]model.Event, 0, len(batch.Events))
	rejected := 0

	for i := range batch.Events {
		e := &batch.Events[i]

		if errMsg := e.Validate(); errMsg != "" {
			log.Printf("ingestion: rejecting event %s: %s", e.EventID, errMsg)
			rejected++
			continue
		}

		// Reject events with timestamps too far in the future (clock skew)
		nowMs := time.Now().UnixMilli()
		if e.TimestampMs > nowMs+5*60*1000 { // 5 minutes tolerance
			log.Printf("ingestion: rejecting event %s: timestamp in future", e.EventID)
			rejected++
			continue
		}

		// Reject events that are too old (7 days)
		if e.TimestampMs < nowMs-7*24*60*60*1000 {
			log.Printf("ingestion: rejecting event %s: timestamp too old", e.EventID)
			rejected++
			continue
		}

		valid = append(valid, *e)
	}

	if len(valid) == 0 {
		log.Printf("ingestion: all %d events in batch %s were rejected", len(batch.Events), batch.BatchID)
		// Still record the batch to prevent re-processing
		_ = p.db.RecordBatch(ctx, batch.BatchID, 0)
		return 0, false, nil
	}

	if err := p.db.InsertEvents(ctx, valid); err != nil {
		return 0, false, fmt.Errorf("insert events: %w", err)
	}

	if err := p.db.RecordBatch(ctx, batch.BatchID, len(valid)); err != nil {
		// Non-fatal: worst case we process the batch twice, which is safe
		log.Printf("ingestion: failed to record batch %s: %v", batch.BatchID, err)
	}

	if rejected > 0 {
		log.Printf("ingestion: batch %s: accepted %d, rejected %d", batch.BatchID, len(valid), rejected)
	}

	return len(valid), false, nil
}
