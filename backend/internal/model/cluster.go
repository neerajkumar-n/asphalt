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
}

// TileResponse is returned by the map tile endpoint.
type TileResponse struct {
	Clusters []AnomalyCluster `json:"clusters"`
}

// ClusterListResponse wraps a list of clusters for the bbox query endpoint.
type ClusterListResponse struct {
	Clusters []AnomalyCluster `json:"clusters"`
}
