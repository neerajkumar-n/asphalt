package model

// Event is the internal representation of a single road anomaly event
// after ingestion and validation.
type Event struct {
	EventID      string  `json:"event_id"`
	TimestampMs  int64   `json:"timestamp_ms"`
	Latitude     float64 `json:"latitude"`
	Longitude    float64 `json:"longitude"`
	AccuracyM    float32 `json:"accuracy_m"`
	Intensity    float32 `json:"intensity"`
	SpeedKmh     float32 `json:"speed_kmh"`
	AnomalyType  string  `json:"anomaly_type"`
	VehicleType  string  `json:"vehicle_type"`
	SDKVersion   string  `json:"sdk_version"`
	SessionID    string  `json:"session_id"`
	SensorSummary SensorSummary `json:"sensor_summary"`
	DeviceMeta   DeviceMeta    `json:"device_meta"`
}

// SensorSummary holds condensed sensor statistics for the detection window.
type SensorSummary struct {
	AccelPeakZ        float32 `json:"accel_peak_z"`
	AccelBaselineZ    float32 `json:"accel_baseline_z"`
	AccelDeltaZ       float32 `json:"accel_delta_z"`
	GyroPeakMagnitude float32 `json:"gyro_peak_magnitude"`
	SampleCount       int     `json:"sample_count"`
	WindowDurationMs  int64   `json:"window_duration_ms"`
}

// DeviceMeta holds anonymous device characteristics.
// No PII fields exist here.
type DeviceMeta struct {
	Platform     string `json:"platform"`
	SDKInt       int    `json:"sdk_int"`
	Manufacturer string `json:"manufacturer"`
	Model        string `json:"model"`
	SensorVendor string `json:"sensor_vendor"`
}

// EventBatch is the top-level structure submitted by the SDK.
type EventBatch struct {
	BatchID       string  `json:"batch_id"`
	SubmittedAtMs int64   `json:"submitted_at_ms"`
	Events        []Event `json:"events"`
}

// IngestResponse is returned after successful batch ingestion.
type IngestResponse struct {
	BatchID       string `json:"batch_id"`
	AcceptedCount int    `json:"accepted_count"`
	Duplicate     bool   `json:"duplicate"`
}

// Validation constants
const (
	MaxEventsPerBatch = 500
	MaxPayloadBytes   = 2 * 1024 * 1024 // 2MB

	AnomalyTypePothole    = "pothole"
	AnomalyTypeBump       = "bump"
	AnomalyTypeRoughPatch = "rough_patch"
	AnomalyTypeUnknown    = "unknown"

	VehicleTypeTwoWheeler   = "two_wheeler"
	VehicleTypeThreeWheeler = "three_wheeler"
	VehicleTypeFourWheeler  = "four_wheeler"
)

var validAnomalyTypes = map[string]bool{
	AnomalyTypePothole:    true,
	AnomalyTypeBump:       true,
	AnomalyTypeRoughPatch: true,
	AnomalyTypeUnknown:    true,
}

var validVehicleTypes = map[string]bool{
	VehicleTypeTwoWheeler:   true,
	VehicleTypeThreeWheeler: true,
	VehicleTypeFourWheeler:  true,
}

// VehicleSignalWeight returns the normalisation weight for a vehicle type.
// Used by the clustering pipeline to adjust per-event intensity.
//
// Four-wheelers have the most stable, well-characterised signal (1.0).
// Two-wheelers are noisier due to lean dynamics (0.8).
// Three-wheelers are noisiest: engine vibration, lateral wobble (0.7).
//
// These weights were set to reflect empirical noise floor differences.
// They should be validated and refined once real-world data is available.
func VehicleSignalWeight(vehicleType string) float32 {
	switch vehicleType {
	case VehicleTypeFourWheeler:
		return 1.0
	case VehicleTypeTwoWheeler:
		return 0.8
	case VehicleTypeThreeWheeler:
		return 0.7
	default:
		return 0.85 // unknown types treated conservatively
	}
}

// Validate performs basic sanity checks on an event.
// Returns an error string if invalid, empty string if valid.
func (e *Event) Validate() string {
	if e.EventID == "" {
		return "event_id is required"
	}
	if e.TimestampMs <= 0 {
		return "timestamp_ms must be positive"
	}
	if e.Latitude < -90 || e.Latitude > 90 {
		return "latitude out of range"
	}
	if e.Longitude < -180 || e.Longitude > 180 {
		return "longitude out of range"
	}
	if e.Intensity < 0 || e.Intensity > 1 {
		return "intensity must be between 0 and 1"
	}
	if !validAnomalyTypes[e.AnomalyType] {
		return "invalid anomaly_type"
	}
	// vehicle_type is optional for backwards compatibility; default to four_wheeler
	if e.VehicleType != "" && !validVehicleTypes[e.VehicleType] {
		return "invalid vehicle_type"
	}
	return ""
}
