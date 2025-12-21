package server

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/chungtau/ledger-gateway/internal/config"
	"github.com/chungtau/ledger-gateway/internal/grpcclient"
)

// Server represents the HTTP server with all its dependencies
type Server struct {
	cfg          *config.Config
	httpServer   *http.Server
	ledgerClient grpcclient.LedgerClient
	redisClient  *redis.Client
}

// New creates a new server instance
func New(cfg *config.Config) (*Server, error) {
	s := &Server{
		cfg: cfg,
	}

	// Initialize ledger client (mock or real)
	if cfg.MockMode {
		log.Println("Using mock ledger client")
		s.ledgerClient = grpcclient.NewMockLedgerClient()
	} else {
		log.Printf("Connecting to ledger-core at %s", cfg.GRPCLedgerAddr)
		client, err := grpcclient.NewGRPCLedgerClient(cfg.GRPCLedgerAddr, cfg.GRPCTimeout)
		if err != nil {
			return nil, fmt.Errorf("failed to create gRPC client: %w", err)
		}
		s.ledgerClient = client
	}

	// Initialize Redis client
	log.Printf("Connecting to Redis at %s", cfg.RedisAddr)
	s.redisClient = redis.NewClient(&redis.Options{
		Addr: cfg.RedisAddr,
	})

	// Test Redis connection
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := s.redisClient.Ping(ctx).Err(); err != nil {
		log.Printf("Warning: Redis connection failed: %v. Rate limiting will be disabled.", err)
		s.redisClient = nil
	}

	// Setup router
	router := SetupRouter(cfg, s.ledgerClient, s.redisClient)

	s.httpServer = &http.Server{
		Addr:         ":" + cfg.GatewayPort,
		Handler:      router,
		ReadTimeout:  15 * time.Second,
		WriteTimeout: 15 * time.Second,
		IdleTimeout:  60 * time.Second,
	}

	return s, nil
}

// Run starts the server and handles graceful shutdown
func (s *Server) Run() error {
	// Channel to listen for OS signals
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)

	// Channel to capture server errors
	errChan := make(chan error, 1)

	// Start server in goroutine
	go func() {
		log.Printf("Starting API Gateway on port %s", s.cfg.GatewayPort)
		log.Printf("Mock mode: %v, Dev mode: %v", s.cfg.MockMode, s.cfg.DevMode)

		if s.cfg.DevMode {
			log.Println("Dev token endpoint available at POST /auth/dev/token")
		}

		if err := s.httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			errChan <- err
		}
	}()

	// Wait for interrupt signal or server error
	select {
	case err := <-errChan:
		return fmt.Errorf("server error: %w", err)
	case sig := <-sigChan:
		log.Printf("Received signal: %v. Shutting down...", sig)
	}

	// Graceful shutdown with timeout
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Shutdown HTTP server
	if err := s.httpServer.Shutdown(ctx); err != nil {
		log.Printf("HTTP server shutdown error: %v", err)
	}

	// Close gRPC client
	if s.ledgerClient != nil {
		if err := s.ledgerClient.Close(); err != nil {
			log.Printf("gRPC client close error: %v", err)
		}
	}

	// Close Redis client
	if s.redisClient != nil {
		if err := s.redisClient.Close(); err != nil {
			log.Printf("Redis client close error: %v", err)
		}
	}

	log.Println("Server gracefully stopped")
	return nil
}
