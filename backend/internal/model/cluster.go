package model

// AnomalyCluster represents a spatial cluster of road events that have been
// aggregated by the clustering pipeline. It is the primary query artifact
// served to map clients.
type AnomalyCluster struct {
	ClusterID    string  `json:"cluster_id"`
	Latitude     float64 `json:"latitude"`
	Longitude    float64 `json:"longitude"`
	AnomalyType  string  `json:"anomaly_type"`
	Confidence   float32 `json:"confidence"`
	EventCount   int     `json:"event_count"`
	AvgIntensity float32 `json:"avg_intensity"`
	LastSeenMs   int64   `json:"last_seen_ms"`
	RadiusM      float64 `json:"radius_m"`

	// VehicleTypeCounts tracks how many events came from each vehicle category.
	// Cross-vehicle confirmation is a strong signal: a pothole reported by both
	// three-wheelers and cars is much more likely to be real than a cluster
	// of reports only from autos (which may be their normal vibration level).
	VehicleTypeCounts map[string]int `json:"vehicle_type_counts,omitempty"`

	// VehicleTypeDiversity is the number of distinct vehicle types that
	// contributed to this cluster. Range: 1 to 3. Higher = more cross-validated.
	VehicleTypeDiversity int `json:"vehicle_type_diversity"`
}

// TileResponse is returned by the map tile endpoint.
type TileResponse struct {
	Clusters []AnomalyCluster `json:"clusters"`
}

// ClusterListResponse wraps a list of clusters for the bbox query endpoint.
type ClusterListResponse struct {
	Clusters []AnomalyCluster `json:"clusters"`
}
