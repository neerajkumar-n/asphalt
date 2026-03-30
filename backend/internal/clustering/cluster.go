package clustering

import (
	"context"
	"log"
	"math"
	"time"

	"github.com/google/uuid"

	"github.com/asphalt-maps/asphalt/backend/internal/model"
	"github.com/asphalt-maps/asphalt/backend/internal/storage"
)

// ClusterRadius defines the spatial grouping radius in metres.
// Events within this radius of each other are candidates for the same cluster.
// 30m is roughly one city block and practical for road-segment-level reporting.
const ClusterRadius = 30.0

// MinEventsForConfidence is the minimum number of independent events required
// before a cluster is considered reliable enough to show on the map.
const MinEventsForConfidence = 2

// MaxAgeMs is the maximum age of events that contribute to cluster confidence.
// Events older than 90 days decay their contribution.
const MaxAgeMs = 90 * 24 * 60 * 60 * 1000

// Runner runs the clustering pipeline periodically in the background.
type Runner struct {
	db       *storage.DB
	interval time.Duration
}

func NewRunner(db *storage.DB, interval time.Duration) *Runner {
	return &Runner{db: db, interval: interval}
}

// Start launches the clustering loop. It blocks until ctx is cancelled.
func (r *Runner) Start(ctx context.Context) {
	ticker := time.NewTicker(r.interval)
	defer ticker.Stop()

	log.Printf("clustering: runner started, interval=%s", r.interval)

	for {
		select {
		case <-ctx.Done():
			log.Println("clustering: runner stopped")
			return
		case <-ticker.C:
			if err := r.RunOnce(ctx); err != nil {
				log.Printf("clustering: run error: %v", err)
			}
		}
	}
}

// RunOnce processes a batch of unclustered events.
// It is safe to call concurrently; duplicate processing is harmless because
// UpsertCluster is idempotent.
func (r *Runner) RunOnce(ctx context.Context) error {
	events, err := r.db.GetUnclusteredEvents(ctx, 5000)
	if err != nil {
		return err
	}
	if len(events) == 0 {
		return nil
	}

	log.Printf("clustering: processing %d unclustered events", len(events))

	clusters := DBSCAN(events, ClusterRadius, MinEventsForConfidence)

	now := time.Now().UnixMilli()
	for _, c := range clusters {
		scored := scoreCluster(c, now)
		if err := r.db.UpsertCluster(ctx, scored); err != nil {
			log.Printf("clustering: upsert error for cluster %s: %v", scored.ClusterID, err)
			continue
		}

		eventIDs := make([]string, len(c))
		for i, e := range c {
			eventIDs[i] = e.EventID
		}
		if err := r.db.MarkEventsClustered(ctx, eventIDs, scored.ClusterID); err != nil {
			log.Printf("clustering: mark error: %v", err)
		}
	}

	log.Printf("clustering: produced %d clusters from %d events", len(clusters), len(events))
	return nil
}

// point is a convenience wrapper for clustering.
type point struct {
	event   model.Event
	visited bool
	noise   bool
	cluster int
}

// DBSCAN implements a simplified geospatial DBSCAN algorithm.
//
// Why DBSCAN over grid-based approaches:
// - Road anomalies form arbitrary-shaped clusters (a long rough patch)
// - No need to choose the number of clusters in advance
// - Naturally handles noise (isolated false-positive events)
//
// Algorithm:
//  1. For each unvisited point, find all neighbours within eps metres
//  2. If neighbour count >= minPts, start a new cluster
//  3. Expand the cluster by recursively adding neighbours of neighbours
//  4. Points with fewer than minPts neighbours are marked as noise
//
// Pseudo-code:
//
//	DBSCAN(events, eps, minPts):
//	  clusters = []
//	  for each event E not yet visited:
//	    mark E as visited
//	    N = neighbours within eps of E
//	    if |N| < minPts:
//	      mark E as noise
//	    else:
//	      C = new cluster
//	      add E to C
//	      for each P in N:
//	        if P not visited:
//	          mark P as visited
//	          M = neighbours within eps of P
//	          if |M| >= minPts:
//	            N = N + M
//	        if P not in any cluster:
//	          add P to C
//	  return clusters
func DBSCAN(events []model.Event, epsMetres float64, minPts int) [][]model.Event {
	pts := make([]point, len(events))
	for i, e := range events {
		pts[i] = point{event: e, cluster: -1}
	}

	clusterID := 0
	var clusters [][]model.Event

	for i := range pts {
		if pts[i].visited {
			continue
		}
		pts[i].visited = true

		neighbours := regionQuery(pts, i, epsMetres)
		if len(neighbours) < minPts {
			pts[i].noise = true
			continue
		}

		var cluster []model.Event
		cluster = append(cluster, pts[i].event)
		pts[i].cluster = clusterID

		queue := make([]int, len(neighbours))
		copy(queue, neighbours)

		for qi := 0; qi < len(queue); qi++ {
			j := queue[qi]
			if !pts[j].visited {
				pts[j].visited = true
				m := regionQuery(pts, j, epsMetres)
				if len(m) >= minPts {
					queue = append(queue, m...)
				}
			}
			if pts[j].cluster == -1 {
				pts[j].cluster = clusterID
				cluster = append(cluster, pts[j].event)
			}
		}

		clusters = append(clusters, cluster)
		clusterID++
	}

	return clusters
}

// regionQuery returns indices of all points within eps metres of point i.
func regionQuery(pts []point, i int, epsMetres float64) []int {
	var result []int
	for j := range pts {
		if j == i {
			continue
		}
		d := haversineMetres(
			pts[i].event.Latitude, pts[i].event.Longitude,
			pts[j].event.Latitude, pts[j].event.Longitude,
		)
		if d <= epsMetres {
			result = append(result, j)
		}
	}
	return result
}

// scoreCluster computes a model.AnomalyCluster from a group of raw events.
//
// Confidence scoring:
//
//   - Base confidence from event count (saturates at ~20 events)
//   - Boosted by consistency of anomaly type across events
//   - Boosted by recency (events older than 90 days decay)
//   - Capped at 1.0
//
// This is a heuristic. Real-world tuning should be driven by ground-truth
// labelled data.
func scoreCluster(events []model.Event, nowMs int64) model.AnomalyCluster {
	if len(events) == 0 {
		return model.AnomalyCluster{}
	}

	// Centroid (simple mean - acceptable for small clusters)
	var sumLat, sumLon float64
	var sumIntensity float32
	var maxTimestampMs int64
	typeCounts := make(map[string]int)

	for _, e := range events {
		sumLat += e.Latitude
		sumLon += e.Longitude
		sumIntensity += e.Intensity
		if e.TimestampMs > maxTimestampMs {
			maxTimestampMs = e.TimestampMs
		}
		typeCounts[e.AnomalyType]++
	}

	n := len(events)
	centLat := sumLat / float64(n)
	centLon := sumLon / float64(n)
	avgIntensity := sumIntensity / float32(n)

	// Dominant anomaly type
	dominantType := ""
	maxCount := 0
	for t, c := range typeCounts {
		if c > maxCount {
			maxCount = c
			dominantType = t
		}
	}

	// Cluster radius = max distance from centroid to any member
	var radiusM float64
	for _, e := range events {
		d := haversineMetres(centLat, centLon, e.Latitude, e.Longitude)
		if d > radiusM {
			radiusM = d
		}
	}

	// Confidence components
	// 1. Event count contribution: log scale, saturates near 1.0 at ~20 events
	countScore := math.Log10(float64(n)+1) / math.Log10(21)

	// 2. Type consistency: fraction of events with the dominant type
	consistencyScore := float64(maxCount) / float64(n)

	// 3. Recency: events older than MaxAgeMs get a reduced weight
	ageMs := nowMs - maxTimestampMs
	recencyScore := 1.0
	if ageMs > 0 {
		recencyScore = math.Max(0, 1.0-float64(ageMs)/float64(MaxAgeMs))
	}

	confidence := float32(math.Min(1.0, countScore*0.5+consistencyScore*0.3+recencyScore*0.2))

	return model.AnomalyCluster{
		ClusterID:    uuid.New().String(),
		Latitude:     centLat,
		Longitude:    centLon,
		AnomalyType:  dominantType,
		Confidence:   confidence,
		EventCount:   n,
		AvgIntensity: avgIntensity,
		LastSeenMs:   maxTimestampMs,
		RadiusM:      math.Max(radiusM, 5.0), // minimum 5m radius
	}
}

// haversineMetres computes the great-circle distance in metres between two
// WGS84 coordinates using the Haversine formula.
//
// This is accurate to within ~0.5% for distances below 1km, which is
// sufficient for our 30m clustering radius.
func haversineMetres(lat1, lon1, lat2, lon2 float64) float64 {
	const earthRadiusM = 6_371_000.0

	dLat := (lat2 - lat1) * math.Pi / 180.0
	dLon := (lon2 - lon1) * math.Pi / 180.0

	a := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(lat1*math.Pi/180.0)*math.Cos(lat2*math.Pi/180.0)*
			math.Sin(dLon/2)*math.Sin(dLon/2)

	c := 2 * math.Atan2(math.Sqrt(a), math.Sqrt(1-a))
	return earthRadiusM * c
}

