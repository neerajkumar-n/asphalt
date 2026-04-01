package model

import (
	"testing"
	"time"
)

// validEvent returns an Event that passes all Validate() checks.
func validEvent() Event {
	return Event{
		EventID:     "evt-test-001",
		TimestampMs: time.Now().UnixMilli(),
		Latitude:    12.97,
		Longitude:   77.59,
		AccuracyM:   8.0,
		Intensity:   0.65,
		SpeedKmh:    35.0,
		AnomalyType: AnomalyTypePothole,
		VehicleType: VehicleTypeFourWheeler,
	}
}

// -------------------------------------------------------------------------
// Happy path
// -------------------------------------------------------------------------

func TestValidate_validEvent(t *testing.T) {
	e := validEvent()
	if msg := e.Validate(); msg != "" {
		t.Errorf("expected valid event to pass, got: %q", msg)
	}
}

// -------------------------------------------------------------------------
// Required fields
// -------------------------------------------------------------------------

func TestValidate_missingEventID(t *testing.T) {
	e := validEvent()
	e.EventID = ""
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for missing event_id")
	}
}

func TestValidate_zeroTimestamp(t *testing.T) {
	e := validEvent()
	e.TimestampMs = 0
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for zero timestamp_ms")
	}
}

func TestValidate_negativeTimestamp(t *testing.T) {
	e := validEvent()
	e.TimestampMs = -1
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for negative timestamp_ms")
	}
}

// -------------------------------------------------------------------------
// Latitude / longitude bounds
// -------------------------------------------------------------------------

func TestValidate_latitudeTooHigh(t *testing.T) {
	e := validEvent()
	e.Latitude = 90.001
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for latitude > 90")
	}
}

func TestValidate_latitudeTooLow(t *testing.T) {
	e := validEvent()
	e.Latitude = -90.001
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for latitude < -90")
	}
}

func TestValidate_latitudeBoundaryValuesAreValid(t *testing.T) {
	for _, lat := range []float64{-90.0, 0.0, 90.0} {
		e := validEvent()
		e.Latitude = lat
		if msg := e.Validate(); msg != "" {
			t.Errorf("latitude %.1f should be valid, got: %q", lat, msg)
		}
	}
}

func TestValidate_longitudeTooHigh(t *testing.T) {
	e := validEvent()
	e.Longitude = 180.001
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for longitude > 180")
	}
}

func TestValidate_longitudeTooLow(t *testing.T) {
	e := validEvent()
	e.Longitude = -180.001
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for longitude < -180")
	}
}

func TestValidate_longitudeBoundaryValuesAreValid(t *testing.T) {
	for _, lon := range []float64{-180.0, 0.0, 180.0} {
		e := validEvent()
		e.Longitude = lon
		if msg := e.Validate(); msg != "" {
			t.Errorf("longitude %.1f should be valid, got: %q", lon, msg)
		}
	}
}

// -------------------------------------------------------------------------
// Intensity bounds
// -------------------------------------------------------------------------

func TestValidate_intensityNegative(t *testing.T) {
	e := validEvent()
	e.Intensity = -0.001
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for intensity < 0")
	}
}

func TestValidate_intensityAboveOne(t *testing.T) {
	e := validEvent()
	e.Intensity = 1.001
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for intensity > 1")
	}
}

func TestValidate_intensityBoundaryValuesAreValid(t *testing.T) {
	for _, intensity := range []float32{0.0, 0.5, 1.0} {
		e := validEvent()
		e.Intensity = intensity
		if msg := e.Validate(); msg != "" {
			t.Errorf("intensity %.1f should be valid, got: %q", intensity, msg)
		}
	}
}

// -------------------------------------------------------------------------
// anomaly_type validation
// -------------------------------------------------------------------------

func TestValidate_invalidAnomalyType(t *testing.T) {
	e := validEvent()
	e.AnomalyType = "crater"
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for invalid anomaly_type")
	}
}

func TestValidate_allValidAnomalyTypes(t *testing.T) {
	types := []string{
		AnomalyTypePothole,
		AnomalyTypeBump,
		AnomalyTypeRoughPatch,
		AnomalyTypeUnknown,
	}
	for _, at := range types {
		e := validEvent()
		e.AnomalyType = at
		if msg := e.Validate(); msg != "" {
			t.Errorf("anomaly_type %q should be valid, got: %q", at, msg)
		}
	}
}

// -------------------------------------------------------------------------
// vehicle_type validation
// -------------------------------------------------------------------------

func TestValidate_emptyVehicleTypeIsAllowed(t *testing.T) {
	// Backwards compatibility: vehicle_type is optional
	e := validEvent()
	e.VehicleType = ""
	if msg := e.Validate(); msg != "" {
		t.Errorf("empty vehicle_type should pass for backwards compat, got: %q", msg)
	}
}

func TestValidate_invalidVehicleTypeIsRejected(t *testing.T) {
	e := validEvent()
	e.VehicleType = "bicycle"
	if msg := e.Validate(); msg == "" {
		t.Error("expected error for unrecognised vehicle_type")
	}
}

func TestValidate_allValidVehicleTypes(t *testing.T) {
	types := []string{
		VehicleTypeTwoWheeler,
		VehicleTypeThreeWheeler,
		VehicleTypeFourWheeler,
	}
	for _, vt := range types {
		e := validEvent()
		e.VehicleType = vt
		if msg := e.Validate(); msg != "" {
			t.Errorf("vehicle_type %q should be valid, got: %q", vt, msg)
		}
	}
}

// -------------------------------------------------------------------------
// VehicleSignalWeight
// -------------------------------------------------------------------------

func TestVehicleSignalWeight_knownTypes(t *testing.T) {
	cases := []struct {
		vehicleType string
		want        float32
	}{
		{VehicleTypeFourWheeler, 1.0},
		{VehicleTypeTwoWheeler, 0.8},
		{VehicleTypeThreeWheeler, 0.7},
	}
	for _, c := range cases {
		got := VehicleSignalWeight(c.vehicleType)
		if got != c.want {
			t.Errorf("VehicleSignalWeight(%q) = %v, want %v", c.vehicleType, got, c.want)
		}
	}
}

func TestVehicleSignalWeight_unknownType_returnsConservativeWeight(t *testing.T) {
	got := VehicleSignalWeight("unicycle")
	if got != 0.85 {
		t.Errorf("unknown vehicle type should return 0.85, got %v", got)
	}
}
