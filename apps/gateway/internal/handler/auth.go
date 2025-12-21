package handler

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

// AuthHandler handles authentication-related endpoints (dev mode only)
type AuthHandler struct {
	jwtSecret string
	devMode   bool
}

// NewAuthHandler creates a new auth handler
func NewAuthHandler(jwtSecret string, devMode bool) *AuthHandler {
	return &AuthHandler{
		jwtSecret: jwtSecret,
		devMode:   devMode,
	}
}

// DevTokenRequest represents the request for generating a dev token
type DevTokenRequest struct {
	UserID    string `json:"user_id"`
	ExpiresIn int    `json:"expires_in"` // Duration in seconds (default: 3600)
}

// DevTokenResponse represents the response containing the generated token
type DevTokenResponse struct {
	Token     string `json:"token"`
	ExpiresAt string `json:"expires_at"`
	UserID    string `json:"user_id"`
}

// GenerateDevToken handles POST /auth/dev/token (only available in DEV_MODE)
func (h *AuthHandler) GenerateDevToken(c *gin.Context) {
	if !h.devMode {
		c.JSON(http.StatusNotFound, gin.H{
			"code":    "NOT_FOUND",
			"message": "Endpoint not available",
		})
		return
	}

	var req DevTokenRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		// If no body provided, use defaults
		req.UserID = ""
		req.ExpiresIn = 0
	}

	// Generate user ID if not provided
	userID := req.UserID
	if userID == "" {
		userID = uuid.New().String()
	}

	// Set default expiration if not provided
	expiresIn := req.ExpiresIn
	if expiresIn <= 0 {
		expiresIn = 3600 // 1 hour default
	}

	expiresAt := time.Now().Add(time.Duration(expiresIn) * time.Second)

	// Create JWT claims
	claims := jwt.MapClaims{
		"sub": userID,
		"iat": time.Now().Unix(),
		"exp": expiresAt.Unix(),
		"nbf": time.Now().Unix(),
	}

	// Create and sign token
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenString, err := token.SignedString([]byte(h.jwtSecret))
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{
			"code":    "INTERNAL_ERROR",
			"message": "Failed to generate token",
		})
		return
	}

	c.JSON(http.StatusOK, DevTokenResponse{
		Token:     tokenString,
		ExpiresAt: expiresAt.UTC().Format(time.RFC3339),
		UserID:    userID,
	})
}
