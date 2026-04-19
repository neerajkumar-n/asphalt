package main

import (
	"context"
	"embed"
	"io/fs"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/asphalt-maps/asphalt/backend/internal/api"
	"github.com/asphalt-maps/asphalt/backend/internal/clustering"
	"github.com/asphalt-maps/asphalt/backend/internal/ingestion"
	"github.com/asphalt-maps/asphalt/backend/internal/storage"
)

//go:embed web
var webFiles embed.FS

func main() {
	// -------------------------------------------------------------------------
	// Configuration from environment variables
	// -------------------------------------------------------------------------
	dsn := getEnv("DATABASE_URL", "postgres://asphalt:asphalt@localhost:5432/asphalt?sslmode=disable")
	addr := getEnv("LISTEN_ADDR", ":8080")
	clusterIntervalStr := getEnv("CLUSTER_INTERVAL", "5m")

	clusterInterval, err := time.ParseDuration(clusterIntervalStr)
	if err != nil {
		log.Fatalf("invalid CLUSTER_INTERVAL: %v", err)
	}

	// -------------------------------------------------------------------------
	// Database
	// -------------------------------------------------------------------------
	log.Println("connecting to database...")
	db, err := storage.New(dsn)
	if err != nil {
		log.Fatalf("database connection failed: %v", err)
	}

	ctx := context.Background()
	if err := db.Migrate(ctx); err != nil {
		log.Fatalf("migration failed: %v", err)
	}
	log.Println("database ready")

	// -------------------------------------------------------------------------
	// Application components
	// -------------------------------------------------------------------------
	processor := ingestion.New(db)
	handler := api.NewHandler(processor, db)

	webFS, err := fs.Sub(webFiles, "web")
	if err != nil {
		log.Fatalf("embed web sub: %v", err)
	}

	mux := http.NewServeMux()
	api.RegisterRoutes(mux, handler, http.FS(webFS))

	server := &http.Server{
		Addr:         addr,
		Handler:      api.Chain(mux, api.LoggingMiddleware, api.CORSMiddleware, api.RateLimitMiddleware),
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	// -------------------------------------------------------------------------
	// Clustering background worker
	// -------------------------------------------------------------------------
	clusterCtx, cancelCluster := context.WithCancel(ctx)
	defer cancelCluster()

	clusterRunner := clustering.NewRunner(db, clusterInterval)
	go clusterRunner.Start(clusterCtx)

	// -------------------------------------------------------------------------
	// HTTP server
	// -------------------------------------------------------------------------
	log.Printf("server listening on %s", addr)

	// Graceful shutdown on SIGTERM / SIGINT
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGTERM, syscall.SIGINT)

	go func() {
		if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server error: %v", err)
		}
	}()

	<-quit
	log.Println("shutting down...")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	if err := server.Shutdown(shutdownCtx); err != nil {
		log.Fatalf("forced shutdown: %v", err)
	}

	log.Println("server stopped")
}

func getEnv(key, defaultVal string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return defaultVal
}
