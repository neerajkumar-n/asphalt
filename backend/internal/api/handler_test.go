package api

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// -------------------------------------------------------------------------
// Health endpoint
// -------------------------------------------------------------------------

func TestHandleHealth_returns200WithStatusOK(t *testing.T) {
	h := &Handler{}
	req := httptest.NewRequest(http.MethodGet, "/v1/health", nil)
	w := httptest.NewRecorder()
	h.handleHealth(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", w.Code)
	}
	if !strings.Contains(w.Body.String(), `"ok"`) {
		t.Errorf("expected 'ok' in response body, got: %s", w.Body.String())
	}
}

// -------------------------------------------------------------------------
// parseTilePath
// -------------------------------------------------------------------------

func TestParseTilePath_valid(t *testing.T) {
	cases := []struct {
		path    string
		z, x, y int
	}{
		{"/v1/map/tiles/14/8192/5460", 14, 8192, 5460},
		{"/v1/map/tiles/0/0/0", 0, 0, 0},
		{"/v1/map/tiles/18/131072/87380", 18, 131072, 87380},
		{"/v1/map/tiles/1/1/0", 1, 1, 0},
	}
	for _, c := range cases {
		z, x, y, err := parseTilePath(c.path)
		if err != nil {
			t.Errorf("parseTilePath(%q) unexpected error: %v", c.path, err)
			continue
		}
		if z != c.z || x != c.x || y != c.y {
			t.Errorf("parseTilePath(%q) = (%d,%d,%d), want (%d,%d,%d)",
				c.path, z, x, y, c.z, c.x, c.y)
		}
	}
}

func TestParseTilePath_invalid(t *testing.T) {
	cases := []struct {
		path   string
		reason string
	}{
		{"/v1/map/tiles/14/8192", "too few parts"},
		{"/v1/map/tiles/abc/8192/5460", "non-integer z"},
		{"/v1/map/tiles/14/abc/5460", "non-integer x"},
		{"/v1/map/tiles/14/8192/abc", "non-integer y"},
		{"/v1/map/tiles", "missing all coordinates"},
		{"/", "empty path"},
	}
	for _, c := range cases {
		_, _, _, err := parseTilePath(c.path)
		if err == nil {
			t.Errorf("parseTilePath(%q) [%s]: expected error, got nil", c.path, c.reason)
		}
	}
}

// -------------------------------------------------------------------------
// tileToLatLon (Mercator slippy map tile projection)
// -------------------------------------------------------------------------

func TestTileToLatLon_zoom0_coversWholeWorld(t *testing.T) {
	minLat, minLon, maxLat, maxLon := tileToLatLon(0, 0, 0)

	if minLon != -180.0 {
		t.Errorf("zoom 0 minLon: got %v, want -180", minLon)
	}
	if maxLon != 180.0 {
		t.Errorf("zoom 0 maxLon: got %v, want 180", maxLon)
	}
	// Mercator projection limits: roughly ±85.05°
	if minLat > -80.0 || maxLat < 80.0 {
		t.Errorf("zoom 0 lat range [%.2f, %.2f] should cover at least [-80, 80]", minLat, maxLat)
	}
}

func TestTileToLatLon_zoom1_tile00_isWestHemisphere(t *testing.T) {
	_, minLon, _, maxLon := tileToLatLon(1, 0, 0)
	if minLon != -180.0 || maxLon != 0.0 {
		t.Errorf("zoom1 tile(0,0) lon: got [%v, %v], want [-180, 0]", minLon, maxLon)
	}
}

func TestTileToLatLon_zoom1_tile10_isEastHemisphere(t *testing.T) {
	_, minLon, _, maxLon := tileToLatLon(1, 1, 0)
	if minLon != 0.0 || maxLon != 180.0 {
		t.Errorf("zoom1 tile(1,0) lon: got [%v, %v], want [0, 180]", minLon, maxLon)
	}
}

func TestTileToLatLon_latBoundsAlwaysOrdered(t *testing.T) {
	// minLat must always be <= maxLat for all tiles
	testCases := [][3]int{
		{14, 8192, 5460},
		{10, 512, 350},
		{1, 0, 1},
		{1, 1, 1},
		{5, 16, 11},
	}
	for _, tc := range testCases {
		minLat, _, maxLat, _ := tileToLatLon(tc[0], tc[1], tc[2])
		if minLat > maxLat {
			t.Errorf("tileToLatLon(%d,%d,%d): minLat %.4f > maxLat %.4f — bounds inverted",
				tc[0], tc[1], tc[2], minLat, maxLat)
		}
	}
}

func TestTileToLatLon_longitudeSpanEqualsExpected(t *testing.T) {
	// At zoom N, each tile covers 360 / 2^N degrees of longitude
	cases := []struct {
		z, x, y   int
		wantSpan float64
	}{
		{0, 0, 0, 360.0},
		{1, 0, 0, 180.0},
		{2, 0, 0, 90.0},
	}
	for _, c := range cases {
		_, minLon, _, maxLon := tileToLatLon(c.z, c.x, c.y)
		span := maxLon - minLon
		if span != c.wantSpan {
			t.Errorf("tileToLatLon(%d,%d,%d) lon span = %v, want %v",
				c.z, c.x, c.y, span, c.wantSpan)
		}
	}
}

// -------------------------------------------------------------------------
// Method enforcement (handler returns 405 before touching DB)
// -------------------------------------------------------------------------

func TestHandleIngestBatch_wrongMethod_returns405(t *testing.T) {
	h := &Handler{}
	for _, method := range []string{http.MethodGet, http.MethodPut, http.MethodDelete} {
		req := httptest.NewRequest(method, "/v1/ingest/batch", nil)
		w := httptest.NewRecorder()
		h.handleIngestBatch(w, req)
		if w.Code != http.StatusMethodNotAllowed {
			t.Errorf("method %s: expected 405, got %d", method, w.Code)
		}
	}
}

func TestHandleGetClusters_wrongMethod_returns405(t *testing.T) {
	h := &Handler{}
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete} {
		req := httptest.NewRequest(method, "/v1/map/clusters", nil)
		w := httptest.NewRecorder()
		h.handleGetClusters(w, req)
		if w.Code != http.StatusMethodNotAllowed {
			t.Errorf("method %s: expected 405, got %d", method, w.Code)
		}
	}
}

func TestHandleGetTile_wrongMethod_returns405(t *testing.T) {
	h := &Handler{}
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete} {
		req := httptest.NewRequest(method, "/v1/map/tiles/14/8192/5460", nil)
		w := httptest.NewRecorder()
		h.handleGetTile(w, req)
		if w.Code != http.StatusMethodNotAllowed {
			t.Errorf("method %s: expected 405, got %d", method, w.Code)
		}
	}
}

// -------------------------------------------------------------------------
// Query parameter validation (returns 400 before touching DB)
// -------------------------------------------------------------------------

func TestHandleGetClusters_missingParams_returns400(t *testing.T) {
	h := &Handler{}
	// No bounding box query parameters → ParseFloat on empty string fails
	req := httptest.NewRequest(http.MethodGet, "/v1/map/clusters", nil)
	w := httptest.NewRecorder()
	h.handleGetClusters(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for missing params, got %d", w.Code)
	}
}

func TestHandleGetClusters_partialParams_returns400(t *testing.T) {
	h := &Handler{}
	// Only min_lat provided, rest missing
	req := httptest.NewRequest(http.MethodGet, "/v1/map/clusters?min_lat=12.0", nil)
	w := httptest.NewRecorder()
	h.handleGetClusters(w, req)
	if w.Code != http.StatusBadRequest {
		t.Errorf("expected 400 for partial params, got %d", w.Code)
	}
}

func TestHandleGetTile_invalidPath_returns400(t *testing.T) {
	h := &Handler{}
	badPaths := []string{
		"/v1/map/tiles/14/notanint/5460",
		"/v1/map/tiles/14/8192",
		"/v1/map/tiles/notanint/8192/5460",
	}
	for _, path := range badPaths {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		w := httptest.NewRecorder()
		h.handleGetTile(w, req)
		if w.Code != http.StatusBadRequest {
			t.Errorf("path %q: expected 400, got %d", path, w.Code)
		}
	}
}

// -------------------------------------------------------------------------
// splitPath helper
// -------------------------------------------------------------------------

func TestSplitPath(t *testing.T) {
	cases := []struct {
		path  string
		parts []string
	}{
		{"/v1/map/tiles/14/8192/5460", []string{"v1", "map", "tiles", "14", "8192", "5460"}},
		{"//a//b//", []string{"a", "b"}},
		{"/single", []string{"single"}},
	}
	for _, c := range cases {
		got := splitPath(c.path)
		if len(got) != len(c.parts) {
			t.Errorf("splitPath(%q): got %v (len %d), want len %d",
				c.path, got, len(got), len(c.parts))
			continue
		}
		for i, p := range c.parts {
			if got[i] != p {
				t.Errorf("splitPath(%q)[%d] = %q, want %q", c.path, i, got[i], p)
			}
		}
	}
}

func TestSplitPath_emptyAndRoot(t *testing.T) {
	for _, path := range []string{"/", "", "/"} {
		got := splitPath(path)
		if len(got) != 0 {
			t.Errorf("splitPath(%q): expected empty result, got %v", path, got)
		}
	}
}
