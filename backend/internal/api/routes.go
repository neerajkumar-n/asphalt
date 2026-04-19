package api

import "net/http"

// RegisterRoutes wires all HTTP routes to the given mux.
// webFS is the filesystem serving the landing page and dashboard HTML.
func RegisterRoutes(mux *http.ServeMux, h *Handler, webFS http.FileSystem) {
	// API routes
	mux.HandleFunc("/v1/ingest/batch", h.handleIngestBatch)
	mux.HandleFunc("/v1/map/clusters", h.handleGetClusters)
	mux.HandleFunc("/v1/map/tiles/", h.handleGetTile) // prefix match for /{z}/{x}/{y}
	mux.HandleFunc("/v1/stats", h.handleGetStats)
	mux.HandleFunc("/v1/health", h.handleHealth)

	// Web UI — landing page at / and dashboard at /dashboard
	mux.Handle("/", http.FileServer(webFS))
}
