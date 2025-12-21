package middleware

import (
	"context"
	"fmt"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
)

// RateLimiter middleware implements sliding window rate limiting using Redis
type RateLimiter struct {
	client  *redis.Client
	rps     int           // Requests per second
	burst   int           // Maximum burst size
	window  time.Duration // Window size (typically 1 second)
}

// NewRateLimiter creates a new rate limiter
func NewRateLimiter(client *redis.Client, rps, burst int) *RateLimiter {
	return &RateLimiter{
		client: client,
		rps:    rps,
		burst:  burst,
		window: time.Second,
	}
}

// Middleware returns the rate limiting middleware
func (rl *RateLimiter) Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		// Get user ID from context (set by auth middleware)
		userID := GetUserID(c)
		if userID == "" {
			// If no user ID, use IP address as fallback
			userID = c.ClientIP()
		}

		// Check rate limit
		allowed, remaining, err := rl.checkLimit(c.Request.Context(), userID)
		if err != nil {
			// On Redis error, log and allow request (fail open)
			requestID := GetRequestID(c)
			fmt.Printf("[%s] Rate limiter Redis error: %v\n", requestID, err)
			c.Next()
			return
		}

		// Set rate limit headers
		c.Header("X-RateLimit-Limit", fmt.Sprintf("%d", rl.rps))
		c.Header("X-RateLimit-Remaining", fmt.Sprintf("%d", remaining))

		if !allowed {
			c.Header("Retry-After", "1")
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"code":    "RATE_LIMITED",
				"message": "Too many requests. Please try again later.",
			})
			return
		}

		c.Next()
	}
}

// checkLimit checks if the request is within rate limits using sliding window log algorithm
func (rl *RateLimiter) checkLimit(ctx context.Context, userID string) (allowed bool, remaining int, err error) {
	now := time.Now().UnixMilli()
	windowStart := now - rl.window.Milliseconds()
	key := fmt.Sprintf("ratelimit:%s", userID)

	// Use Redis pipeline for atomic operations
	pipe := rl.client.Pipeline()

	// Remove old entries outside the window
	pipe.ZRemRangeByScore(ctx, key, "0", fmt.Sprintf("%d", windowStart))

	// Add current request
	pipe.ZAdd(ctx, key, redis.Z{
		Score:  float64(now),
		Member: now,
	})

	// Count requests in current window
	countCmd := pipe.ZCard(ctx, key)

	// Set TTL on the key (2x window to handle sliding)
	pipe.Expire(ctx, key, 2*rl.window)

	// Execute pipeline
	_, err = pipe.Exec(ctx)
	if err != nil {
		return false, 0, err
	}

	count := int(countCmd.Val())
	remaining = rl.burst - count
	if remaining < 0 {
		remaining = 0
	}

	// Allow if under burst limit
	allowed = count <= rl.burst

	return allowed, remaining, nil
}

// Close closes the Redis client connection
func (rl *RateLimiter) Close() error {
	return rl.client.Close()
}
