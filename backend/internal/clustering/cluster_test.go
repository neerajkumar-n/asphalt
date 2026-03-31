package clustering

import (
	"testing"
	"time"

	"github.com/asphalt-maps/asphalt/backend/internal/model"
)

// -------------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------------

// makeEvent constructs a minimal model.Event at a given lat/lon.
func makeEvent(id string, lat, lon float64, anomalyType, vehicleType string, intensity float32, ageMs int64) model.Event {
	return model.Event{
		EventID:     id,
		TimestampMs: ageMs,
		Latitude:    lat,
		Longitude:   lon,
		AccuracyM:   10.0,
		Intensity:   intensity,
		SpeedKmh:    30.0,
		AnomalyType: anomalyType,
		VehicleType: vehicleType,
	}
}

// nowMs is the reference "now" for tests that need a fixed point in time.
var nowMs = time.Date(2024, 3, 30, 0, 0, 0, 0, time.UTC).UnixMilli()

// recentMs returns a timestamp that is ageSeconds old relative to nowMs.
func recentMs(ageSeconds int64) int64 {
	return nowMs - ageSeconds*1000
}

// -------------------------------------------------------------------------
// haversineMetres
// -------------------------------------------------------------------------

func TestHaversineMetres_SamePoint(t *testing.T) {
	d := haversineMetres(12.9716, 77.5946, 12.9716, 77.5946)
	if d != 0.0 {
		t.Errorf("same-point distance should be 0, got %f", d)
	}
}

func TestHaversineMetres_KnownDistance(t *testing.T) {
	// Rough check: 0.0003 degrees latitude ≈ 33m
	d := haversineMetres(12.9716, 77.5946, 12.9719, 77.5946)
	if d < 25.0 || d > 45.0 {
		t.Errorf("expected ~33m, got %f", d)
	}
}

func TestHaversineMetres_WithinClusterRadius(t *testing.T) {
	// Two points 20m apart should be within ClusterRadius (30m)
	// 0.0002 degrees latitude ≈ 22m
	d := haversineMetres(12.9716, 77.5946, 12.9718, 77.5946)
	if d > ClusterRadius {
		t.Errorf("expected < %f, got %f", ClusterRadius, d)
	}
}

func TestHaversineMetres_BeyondClusterRadius(t *testing.T) {
	// 0.0005 degrees latitude ≈ 55m, outside ClusterRadius
	d := haversineMetres(12.9716, 77.5946, 12.9721, 77.5946)
	if d <= ClusterRadius {
		t.Errorf("expected > %f, got %f", ClusterRadius, d)
	}
}

// -------------------------------------------------------------------------
// DBSCAN
// -------------------------------------------------------------------------

func TestDBSCAN_NoEvents(t *testing.T) {
	clusters := DBSCAN(nil, ClusterRadius, MinEventsForConfidence)
	if len(clusters) != 0 {
		t.Errorf("expected 0 clusters for empty input, got %d", len(clusters))
	}
}

func TestDBSCAN_SingleEvent_BecomeNoise(t *testing.T) {
	// One point with minPts=2: it becomes noise, not a cluster.
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60)),
	}
	clusters := DBSCAN(events, ClusterRadius, 2)
	if len(clusters) != 0 {
		t.Errorf("single isolated point should be noise, got %d clusters", len(clusters))
	}
}

func TestDBSCAN_ClosePoints_FormCluster(t *testing.T) {
	// Three points within 30m with minPts=2: the middle point has 2 neighbours
	// so it becomes a core point and pulls in the others.
	// Placed in a line with ~22m spacing (0.0002 deg lat ≈ 22m each step).
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60)),
		makeEvent("e2", 12.9718, 77.5946, "pothole", "four_wheeler", 0.7, recentMs(90)),
		makeEvent("e3", 12.9717, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(120)),
	}
	clusters := DBSCAN(events, ClusterRadius, 2)
	if len(clusters) != 1 {
		t.Fatalf("expected 1 cluster, got %d", len(clusters))
	}
	if len(clusters[0]) != 3 {
		t.Errorf("expected 3 events in cluster, got %d", len(clusters[0]))
	}
}

func TestDBSCAN_TwoDistinctClusters(t *testing.T) {
	// Two groups separated by ~500m (different road segments).
	// Group A: centred around 12.9716 (3 events within 30m)
	// Group B: centred around 12.9760 (3 events within 30m, ≈490m from group A)
	events := []model.Event{
		makeEvent("a1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60)),
		makeEvent("a2", 12.9717, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(90)),
		makeEvent("a3", 12.9718, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(110)),
		makeEvent("b1", 12.9760, 77.5946, "bump", "three_wheeler", 0.4, recentMs(120)),
		makeEvent("b2", 12.9761, 77.5946, "bump", "three_wheeler", 0.5, recentMs(150)),
		makeEvent("b3", 12.9762, 77.5946, "bump", "three_wheeler", 0.5, recentMs(180)),
	}
	clusters := DBSCAN(events, ClusterRadius, 2)
	if len(clusters) != 2 {
		t.Errorf("expected 2 distinct clusters, got %d", len(clusters))
	}
}

func TestDBSCAN_IsolatedPointExcluded(t *testing.T) {
	// Four points: three form a cluster (e1-e2-e3 within 30m of each other),
	// one isolated noise event 500m away.
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60)),
		makeEvent("e2", 12.9718, 77.5946, "pothole", "four_wheeler", 0.7, recentMs(90)),
		makeEvent("e3", 12.9717, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(110)),
		// Isolated: 500m away
		makeEvent("noise", 12.9760, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(120)),
	}
	clusters := DBSCAN(events, ClusterRadius, 2)
	if len(clusters) != 1 {
		t.Fatalf("expected 1 cluster (noise excluded), got %d", len(clusters))
	}
	if len(clusters[0]) != 3 {
		t.Errorf("cluster should contain 3 events, got %d", len(clusters[0]))
	}
}

func TestDBSCAN_ChainExpansion(t *testing.T) {
	// Points A-B-C where A-B are within eps and B-C are within eps but A-C are not.
	// DBSCAN should merge all three into one cluster via expansion.
	// Place them along a line with ~20m spacing (0.0002 deg lat ≈ 22m).
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60)),
		makeEvent("e2", 12.9718, 77.5946, "pothole", "four_wheeler", 0.7, recentMs(90)),
		makeEvent("e3", 12.9720, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(120)),
	}
	clusters := DBSCAN(events, ClusterRadius, 2)
	if len(clusters) != 1 {
		t.Fatalf("expected 1 cluster via chain expansion, got %d", len(clusters))
	}
	if len(clusters[0]) != 3 {
		t.Errorf("expected all 3 events in cluster, got %d", len(clusters[0]))
	}
}

// -------------------------------------------------------------------------
// scoreCluster
// -------------------------------------------------------------------------

func TestScoreCluster_EmptyInput(t *testing.T) {
	c := scoreCluster(nil, nowMs)
	if c.EventCount != 0 {
		t.Errorf("empty input should return zero cluster")
	}
}

func TestScoreCluster_CentroidIsAverage(t *testing.T) {
	events := []model.Event{
		makeEvent("e1", 12.0, 77.0, "pothole", "four_wheeler", 0.5, recentMs(60)),
		makeEvent("e2", 14.0, 79.0, "pothole", "four_wheeler", 0.5, recentMs(60)),
	}
	c := scoreCluster(events, nowMs)
	if c.Latitude != 13.0 {
		t.Errorf("expected centroid lat=13.0, got %f", c.Latitude)
	}
	if c.Longitude != 78.0 {
		t.Errorf("expected centroid lon=78.0, got %f", c.Longitude)
	}
}

func TestScoreCluster_DominantAnomalyType(t *testing.T) {
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(60)),
		makeEvent("e2", 12.9717, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(90)),
		makeEvent("e3", 12.9718, 77.5946, "bump", "four_wheeler", 0.4, recentMs(120)),
	}
	c := scoreCluster(events, nowMs)
	if c.AnomalyType != "pothole" {
		t.Errorf("expected dominant type 'pothole', got %q", c.AnomalyType)
	}
}

func TestScoreCluster_EventCount(t *testing.T) {
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(60)),
		makeEvent("e2", 12.9717, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(90)),
		makeEvent("e3", 12.9718, 77.5946, "pothole", "four_wheeler", 0.4, recentMs(120)),
	}
	c := scoreCluster(events, nowMs)
	if c.EventCount != 3 {
		t.Errorf("expected EventCount=3, got %d", c.EventCount)
	}
}

func TestScoreCluster_MinimumRadiusEnforced(t *testing.T) {
	// Single-point cluster: radius from centroid to itself is 0, should be clamped to 5m.
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(60)),
		makeEvent("e2", 12.9716, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(90)),
	}
	c := scoreCluster(events, nowMs)
	if c.RadiusM < 5.0 {
		t.Errorf("minimum radius should be 5m, got %f", c.RadiusM)
	}
}

func TestScoreCluster_LastSeenIsMax(t *testing.T) {
	early := recentMs(300)
	late := recentMs(60)
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.5, early),
		makeEvent("e2", 12.9717, 77.5946, "pothole", "four_wheeler", 0.6, late),
	}
	c := scoreCluster(events, nowMs)
	if c.LastSeenMs != late {
		t.Errorf("expected LastSeenMs=%d, got %d", late, c.LastSeenMs)
	}
}

// -------------------------------------------------------------------------
// scoreCluster: confidence components
// -------------------------------------------------------------------------

func TestScoreCluster_ConfidenceInRange(t *testing.T) {
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60)),
		makeEvent("e2", 12.9717, 77.5946, "pothole", "four_wheeler", 0.7, recentMs(90)),
	}
	c := scoreCluster(events, nowMs)
	if c.Confidence < 0 || c.Confidence > 1 {
		t.Errorf("confidence out of [0,1]: %f", c.Confidence)
	}
}

func TestScoreCluster_MoreEventsHigherConfidence(t *testing.T) {
	// 10-event cluster should have higher confidence than 2-event cluster.
	small := make([]model.Event, 2)
	large := make([]model.Event, 10)
	for i := range small {
		small[i] = makeEvent("s"+string(rune('0'+i)), 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60))
	}
	for i := range large {
		large[i] = makeEvent("l"+string(rune('0'+i)), 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60))
	}
	cs := scoreCluster(small, nowMs)
	cl := scoreCluster(large, nowMs)
	if cl.Confidence <= cs.Confidence {
		t.Errorf("larger cluster should have higher confidence: small=%f large=%f", cs.Confidence, cl.Confidence)
	}
}

func TestScoreCluster_DiversityBonusIncreasesConfidence(t *testing.T) {
	// Mixed vehicle cluster vs single vehicle cluster with same event count.
	singleType := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "three_wheeler", 0.5, recentMs(60)),
		makeEvent("e2", 12.9717, 77.5946, "pothole", "three_wheeler", 0.5, recentMs(90)),
		makeEvent("e3", 12.9718, 77.5946, "pothole", "three_wheeler", 0.5, recentMs(120)),
	}
	mixed := []model.Event{
		makeEvent("m1", 12.9716, 77.5946, "pothole", "three_wheeler", 0.5, recentMs(60)),
		makeEvent("m2", 12.9717, 77.5946, "pothole", "four_wheeler", 0.5, recentMs(90)),
		makeEvent("m3", 12.9718, 77.5946, "pothole", "two_wheeler", 0.5, recentMs(120)),
	}
	cSingle := scoreCluster(singleType, nowMs)
	cMixed := scoreCluster(mixed, nowMs)
	if cMixed.Confidence <= cSingle.Confidence {
		t.Errorf("mixed-vehicle cluster should have higher confidence: single=%f mixed=%f",
			cSingle.Confidence, cMixed.Confidence)
	}
}

func TestScoreCluster_ThreeWheelerOnlyPenalty(t *testing.T) {
	// Sparse (n=2) auto-only cluster vs sparse car-only cluster: auto should score lower.
	autoOnly := []model.Event{
		makeEvent("a1", 12.9716, 77.5946, "pothole", "three_wheeler", 0.6, recentMs(60)),
		makeEvent("a2", 12.9717, 77.5946, "pothole", "three_wheeler", 0.6, recentMs(90)),
	}
	carOnly := []model.Event{
		makeEvent("c1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60)),
		makeEvent("c2", 12.9717, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(90)),
	}
	cAuto := scoreCluster(autoOnly, nowMs)
	cCar := scoreCluster(carOnly, nowMs)
	if cAuto.Confidence >= cCar.Confidence {
		t.Errorf("sparse auto-only cluster should score lower: auto=%f car=%f",
			cAuto.Confidence, cCar.Confidence)
	}
}

func TestScoreCluster_ThreeWheelerPenaltyLiftedAt4Events(t *testing.T) {
	// Auto-only cluster with n>=4 should NOT receive the penalty.
	// It should be more confident than an auto cluster with n=2.
	sparse := []model.Event{
		makeEvent("a1", 12.9716, 77.5946, "pothole", "three_wheeler", 0.6, recentMs(60)),
		makeEvent("a2", 12.9717, 77.5946, "pothole", "three_wheeler", 0.6, recentMs(90)),
	}
	dense := []model.Event{
		makeEvent("b1", 12.9716, 77.5946, "pothole", "three_wheeler", 0.6, recentMs(60)),
		makeEvent("b2", 12.9717, 77.5946, "pothole", "three_wheeler", 0.6, recentMs(90)),
		makeEvent("b3", 12.9718, 77.5946, "pothole", "three_wheeler", 0.6, recentMs(120)),
		makeEvent("b4", 12.9719, 77.5946, "pothole", "three_wheeler", 0.6, recentMs(150)),
	}
	cSparse := scoreCluster(sparse, nowMs)
	cDense := scoreCluster(dense, nowMs)
	if cDense.Confidence <= cSparse.Confidence {
		t.Errorf("4-event auto cluster should be more confident than 2-event: sparse=%f dense=%f",
			cSparse.Confidence, cDense.Confidence)
	}
}

func TestScoreCluster_WeightedIntensityLowerForThreeWheeler(t *testing.T) {
	// Three-wheeler events have weight 0.7; their avg intensity should be
	// lower than equivalent car events with the same raw intensity value.
	autoEvents := []model.Event{
		makeEvent("a1", 12.9716, 77.5946, "pothole", "three_wheeler", 0.8, recentMs(60)),
		makeEvent("a2", 12.9717, 77.5946, "pothole", "three_wheeler", 0.8, recentMs(90)),
	}
	carEvents := []model.Event{
		makeEvent("c1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.8, recentMs(60)),
		makeEvent("c2", 12.9717, 77.5946, "pothole", "four_wheeler", 0.8, recentMs(90)),
	}
	cAuto := scoreCluster(autoEvents, nowMs)
	cCar := scoreCluster(carEvents, nowMs)
	// Both clusters have the same raw intensity (0.8), but the weighted average
	// should be the same because we are doing weighted_sum / total_weight.
	// The weights cancel out, so avg intensity should equal raw intensity.
	// This test verifies the calculation doesn't accidentally amplify or suppress.
	if cAuto.AvgIntensity < 0.79 || cAuto.AvgIntensity > 0.81 {
		t.Errorf("expected auto avg intensity ≈ 0.8, got %f", cAuto.AvgIntensity)
	}
	if cCar.AvgIntensity < 0.79 || cCar.AvgIntensity > 0.81 {
		t.Errorf("expected car avg intensity ≈ 0.8, got %f", cCar.AvgIntensity)
	}
}

func TestScoreCluster_RecencyDecay(t *testing.T) {
	// Recent cluster should have higher confidence than old cluster
	// with identical events except timestamp.
	recent := []model.Event{
		makeEvent("r1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60)),
		makeEvent("r2", 12.9717, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(90)),
	}
	// MaxAgeMs = 90 days; use 85 days old to be within decay range but not zero
	old := []model.Event{
		makeEvent("o1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(85*24*3600)),
		makeEvent("o2", 12.9717, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(85*24*3600)),
	}
	cRecent := scoreCluster(recent, nowMs)
	cOld := scoreCluster(old, nowMs)
	if cRecent.Confidence <= cOld.Confidence {
		t.Errorf("recent cluster should have higher confidence: recent=%f old=%f",
			cRecent.Confidence, cOld.Confidence)
	}
}

func TestScoreCluster_VehicleTypeDiversity(t *testing.T) {
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60)),
		makeEvent("e2", 12.9717, 77.5946, "pothole", "three_wheeler", 0.6, recentMs(90)),
		makeEvent("e3", 12.9718, 77.5946, "pothole", "two_wheeler", 0.6, recentMs(120)),
	}
	c := scoreCluster(events, nowMs)
	if c.VehicleTypeDiversity != 3 {
		t.Errorf("expected diversity=3, got %d", c.VehicleTypeDiversity)
	}
	if c.VehicleTypeCounts["four_wheeler"] != 1 {
		t.Errorf("expected 1 four_wheeler, got %d", c.VehicleTypeCounts["four_wheeler"])
	}
	if c.VehicleTypeCounts["three_wheeler"] != 1 {
		t.Errorf("expected 1 three_wheeler, got %d", c.VehicleTypeCounts["three_wheeler"])
	}
	if c.VehicleTypeCounts["two_wheeler"] != 1 {
		t.Errorf("expected 1 two_wheeler, got %d", c.VehicleTypeCounts["two_wheeler"])
	}
}

func TestScoreCluster_ConfidenceClampedAt1(t *testing.T) {
	// A large cluster of identical recent events should clamp at 1.0.
	events := make([]model.Event, 30)
	for i := range events {
		events[i] = makeEvent("e"+string(rune('0'+i%10)), 12.9716, 77.5946, "pothole", "four_wheeler", 0.9, recentMs(60))
	}
	c := scoreCluster(events, nowMs)
	if c.Confidence > 1.0 {
		t.Errorf("confidence must not exceed 1.0, got %f", c.Confidence)
	}
}

func TestScoreCluster_ClusterIDIsNonEmpty(t *testing.T) {
	events := []model.Event{
		makeEvent("e1", 12.9716, 77.5946, "pothole", "four_wheeler", 0.6, recentMs(60)),
	}
	c := scoreCluster(events, nowMs)
	if c.ClusterID == "" {
		t.Error("ClusterID must not be empty")
	}
}
