package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"

	"github.com/chungtau/ledger-gateway/internal/grpcclient"
)

// HealthHandler handles health check endpoints
type HealthHandler struct {
	ledgerClient grpcclient.LedgerClient
	redisClient  *redis.Client
}

// NewHealthHandler creates a new health handler
func NewHealthHandler(ledgerClient grpcclient.LedgerClient, redisClient *redis.Client) *HealthHandler {
	return &HealthHandler{
		ledgerClient: ledgerClient,
		redisClient:  redisClient,
	}
}

// Liveness handles the basic liveness probe
// GET /health
func (h *HealthHandler) Liveness(c *gin.Context) {
	c.JSON(http.StatusOK, gin.H{
		"status": "ok",
	})
}

// Readiness handles the readiness probe (checks dependencies)
// GET /health/ready
func (h *HealthHandler) Readiness(c *gin.Context) {
	checks := make(map[string]string)
	healthy := true

	// Check Redis connectivity
	if h.redisClient != nil {
		if err := h.redisClient.Ping(c.Request.Context()).Err(); err != nil {
			checks["redis"] = "unhealthy: " + err.Error()
			healthy = false
		} else {
			checks["redis"] = "healthy"
		}
	} else {
		checks["redis"] = "not configured"
	}

	// Check gRPC connectivity (try a simple operation)
	if h.ledgerClient != nil {
		// We don't have a health check RPC, so we just report the client is configured
		checks["ledger-core"] = "configured"
	} else {
		checks["ledger-core"] = "not configured"
		healthy = false
	}

	status := http.StatusOK
	statusText := "ready"
	if !healthy {
		status = http.StatusServiceUnavailable
		statusText = "not ready"
	}

	c.JSON(status, gin.H{
		"status": statusText,
		"checks": checks,
	})
}
