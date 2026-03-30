package api

import "net/http"

// RegisterRoutes wires all HTTP routes to the given mux.
func RegisterRoutes(mux *http.ServeMux, h *Handler) {
	mux.HandleFunc("/v1/ingest/batch", h.handleIngestBatch)
	mux.HandleFunc("/v1/map/clusters", h.handleGetClusters)
	mux.HandleFunc("/v1/map/tiles/", h.handleGetTile) // prefix match for /{z}/{x}/{y}
	mux.HandleFunc("/v1/health", h.handleHealth)
}
