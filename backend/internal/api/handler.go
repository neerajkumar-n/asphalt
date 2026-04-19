package api

import (
	"encoding/json"
	"fmt"
	"log"
	"math"
	"net/http"
	"strconv"
	"strings"

	"github.com/asphalt-maps/asphalt/backend/internal/ingestion"
	"github.com/asphalt-maps/asphalt/backend/internal/model"
	"github.com/asphalt-maps/asphalt/backend/internal/storage"
)

// Handler holds the dependencies for all HTTP handlers.
type Handler struct {
	processor *ingestion.Processor
	db        *storage.DB
}

func NewHandler(processor *ingestion.Processor, db *storage.DB) *Handler {
	return &Handler{processor: processor, db: db}
}

// handleIngestBatch handles POST /v1/ingest/batch
func (h *Handler) handleIngestBatch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	// Enforce payload size limit
	r.Body = http.MaxBytesReader(w, r.Body, model.MaxPayloadBytes)

	var batch model.EventBatch
	if err := json.NewDecoder(r.Body).Decode(&batch); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}

	accepted, duplicate, err := h.processor.ProcessBatch(r.Context(), batch)
	if err != nil {
		log.Printf("api: ingest error: %v", err)
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	writeJSON(w, http.StatusOK, model.IngestResponse{
		BatchID:       batch.BatchID,
		AcceptedCount: accepted,
		Duplicate:     duplicate,
	})
}

// handleGetClusters handles GET /v1/map/clusters
func (h *Handler) handleGetClusters(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	q := r.URL.Query()
	minLat, err1 := strconv.ParseFloat(q.Get("min_lat"), 64)
	minLon, err2 := strconv.ParseFloat(q.Get("min_lon"), 64)
	maxLat, err3 := strconv.ParseFloat(q.Get("max_lat"), 64)
	maxLon, err4 := strconv.ParseFloat(q.Get("max_lon"), 64)

	if err1 != nil || err2 != nil || err3 != nil || err4 != nil {
		writeError(w, http.StatusBadRequest, "min_lat, min_lon, max_lat, max_lon are required float parameters")
		return
	}

	limit := 200
	if ls := q.Get("limit"); ls != "" {
		if l, err := strconv.Atoi(ls); err == nil && l > 0 && l <= 1000 {
			limit = l
		}
	}

	clusters, err := h.db.GetClusters(r.Context(), minLat, minLon, maxLat, maxLon, limit)
	if err != nil {
		log.Printf("api: get clusters error: %v", err)
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	if clusters == nil {
		clusters = []model.AnomalyCluster{}
	}

	writeJSON(w, http.StatusOK, model.ClusterListResponse{Clusters: clusters})
}

// handleGetTile handles GET /v1/map/tiles/{z}/{x}/{y}
func (h *Handler) handleGetTile(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}

	z, x, y, err := parseTilePath(r.URL.Path)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid tile coordinates: "+err.Error())
		return
	}

	minConfidence := float32(0.3)
	if mcs := r.URL.Query().Get("min_confidence"); mcs != "" {
		if mc, err := strconv.ParseFloat(mcs, 32); err == nil {
			minConfidence = float32(mc)
		}
	}

	minLat, minLon, maxLat, maxLon := tileToLatLon(z, x, y)

	clusters, err := h.db.GetClustersForTile(r.Context(), minLat, minLon, maxLat, maxLon, minConfidence)
	if err != nil {
		log.Printf("api: get tile error: %v", err)
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}

	if len(clusters) == 0 {
		w.WriteHeader(http.StatusNotFound)
		return
	}

	writeJSON(w, http.StatusOK, model.TileResponse{Clusters: clusters})
}

// handleGetStats handles GET /v1/stats
func (h *Handler) handleGetStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	stats, err := h.db.GetDashboardStats(r.Context())
	if err != nil {
		log.Printf("api: stats error: %v", err)
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, stats)
}

// handleHealth handles GET /v1/health
func (h *Handler) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}

// parseTilePath extracts z, x, y from a path like /v1/map/tiles/14/8192/5460
func parseTilePath(path string) (z, x, y int, err error) {
	parts := splitPath(path)
	if len(parts) < 3 {
		return 0, 0, 0, fmt.Errorf("expected /v1/map/tiles/{z}/{x}/{y}")
	}
	zs, xs, ys := parts[len(parts)-3], parts[len(parts)-2], parts[len(parts)-1]
	zi, e1 := strconv.Atoi(zs)
	xi, e2 := strconv.Atoi(xs)
	yi, e3 := strconv.Atoi(ys)
	if e1 != nil || e2 != nil || e3 != nil {
		return 0, 0, 0, fmt.Errorf("z, x, y must be integers")
	}
	return zi, xi, yi, nil
}

// tileToLatLon converts slippy map tile coordinates to WGS84 bounding box.
func tileToLatLon(z, x, y int) (minLat, minLon, maxLat, maxLon float64) {
	n := float64(int(1) << z)
	minLon = float64(x)/n*360.0 - 180.0
	maxLon = float64(x+1)/n*360.0 - 180.0

	latRad := math.Atan(math.Sinh(math.Pi * (1 - 2*float64(y)/n)))
	minLat = latRad * 180.0 / math.Pi

	latRadMax := math.Atan(math.Sinh(math.Pi * (1 - 2*float64(y+1)/n)))
	maxLat = latRadMax * 180.0 / math.Pi

	if minLat > maxLat {
		minLat, maxLat = maxLat, minLat
	}
	return
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		log.Printf("api: encode response: %v", err)
	}
}

func writeError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func splitPath(path string) []string {
	// Use strings.Split and filter empty segments from leading/trailing slashes
	raw := strings.Split(path, "/")
	var parts []string
	for _, p := range raw {
		if p != "" {
			parts = append(parts, p)
		}
	}
	return parts
}
