package server

import (
	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"

	"github.com/chungtau/ledger-gateway/internal/config"
	"github.com/chungtau/ledger-gateway/internal/grpcclient"
	"github.com/chungtau/ledger-gateway/internal/handler"
	"github.com/chungtau/ledger-gateway/internal/middleware"
)

// SetupRouter creates and configures the Gin router
func SetupRouter(cfg *config.Config, ledgerClient grpcclient.LedgerClient, redisClient *redis.Client) *gin.Engine {
	// Set Gin mode based on environment
	if cfg.DevMode {
		gin.SetMode(gin.DebugMode)
	} else {
		gin.SetMode(gin.ReleaseMode)
	}

	router := gin.New()

	// Global middleware
	router.Use(middleware.Recovery())
	router.Use(middleware.Logging())

	// Create handlers
	healthHandler := handler.NewHealthHandler(ledgerClient, redisClient)
	transactionHandler := handler.NewTransactionHandler(ledgerClient)
	balanceHandler := handler.NewBalanceHandler(ledgerClient)
	authHandler := handler.NewAuthHandler(cfg.JWTSecret, cfg.DevMode)

	// Health check endpoints (no auth required)
	router.GET("/health", healthHandler.Liveness)
	router.GET("/health/ready", healthHandler.Readiness)

	// Dev-only auth endpoint (only available in DEV_MODE)
	if cfg.DevMode {
		router.POST("/auth/dev/token", authHandler.GenerateDevToken)
	}

	// API v1 routes (auth required)
	v1 := router.Group("/v1")
	{
		// Apply auth middleware
		v1.Use(middleware.Auth(cfg.JWTSecret))

		// Apply rate limiting if Redis is available
		if redisClient != nil {
			rateLimiter := middleware.NewRateLimiter(redisClient, cfg.RateLimitRPS, cfg.RateLimitBurst)
			v1.Use(rateLimiter.Middleware())
		}

		// Transaction endpoints
		v1.POST("/transactions", transactionHandler.Create)

		// Account endpoints
		accounts := v1.Group("/accounts")
		{
			accounts.GET("/:id/balance", balanceHandler.Get)
		}
	}

	return router
}
