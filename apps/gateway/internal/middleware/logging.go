package middleware

import (
	"context"
	"log"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
)

const (
	RequestIDKey     = "request_id"
	RequestIDHeader  = "X-Request-ID"
)

// Logging middleware logs request details and generates/propagates Request-ID
func Logging() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()

		// Get or generate Request-ID
		requestID := c.GetHeader(RequestIDHeader)
		if requestID == "" {
			requestID = uuid.New().String()
		}

		// Set Request-ID in response header
		c.Header(RequestIDHeader, requestID)

		// Store Request-ID in context for downstream use
		c.Set(RequestIDKey, requestID)

		// Create a new context with request ID for gRPC propagation
		ctx := context.WithValue(c.Request.Context(), RequestIDKey, requestID)
		c.Request = c.Request.WithContext(ctx)

		// Process request
		c.Next()

		// Calculate latency
		latency := time.Since(start)

		// Log request details
		log.Printf("[%s] %s %s %d %v %s",
			requestID,
			c.Request.Method,
			c.Request.URL.Path,
			c.Writer.Status(),
			latency,
			c.ClientIP(),
		)
	}
}

// GetRequestID retrieves the request ID from the gin context
func GetRequestID(c *gin.Context) string {
	if requestID, exists := c.Get(RequestIDKey); exists {
		return requestID.(string)
	}
	return ""
}
