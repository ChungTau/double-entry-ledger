package middleware

import (
	"net/http"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

const (
	UserIDKey = "user_id"
	ClaimsKey = "claims"
)

// Auth middleware validates JWT tokens using HS256
func Auth(jwtSecret string) gin.HandlerFunc {
	return func(c *gin.Context) {
		// Extract Authorization header
		authHeader := c.GetHeader("Authorization")
		if authHeader == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code":    "UNAUTHORIZED",
				"message": "Authorization header required",
			})
			return
		}

		// Parse "Bearer <token>" format
		parts := strings.SplitN(authHeader, " ", 2)
		if len(parts) != 2 || strings.ToLower(parts[0]) != "bearer" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code":    "UNAUTHORIZED",
				"message": "Invalid authorization format. Use: Bearer <token>",
			})
			return
		}

		tokenString := parts[1]

		// Parse and validate JWT token
		token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
			// Validate signing method is HMAC (HS256)
			if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
				return nil, jwt.ErrSignatureInvalid
			}
			return []byte(jwtSecret), nil
		})

		if err != nil {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code":    "UNAUTHORIZED",
				"message": "Invalid or expired token",
			})
			return
		}

		// Extract claims
		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok || !token.Valid {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code":    "UNAUTHORIZED",
				"message": "Invalid token claims",
			})
			return
		}

		// Extract subject (user_id) from claims
		sub, ok := claims["sub"].(string)
		if !ok || sub == "" {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{
				"code":    "UNAUTHORIZED",
				"message": "Token missing subject claim",
			})
			return
		}

		// Store user_id and claims in context for downstream handlers
		c.Set(UserIDKey, sub)
		c.Set(ClaimsKey, claims)

		c.Next()
	}
}

// GetUserID retrieves the user ID from the gin context
func GetUserID(c *gin.Context) string {
	if userID, exists := c.Get(UserIDKey); exists {
		return userID.(string)
	}
	return ""
}

// GetClaims retrieves the JWT claims from the gin context
func GetClaims(c *gin.Context) jwt.MapClaims {
	if claims, exists := c.Get(ClaimsKey); exists {
		return claims.(jwt.MapClaims)
	}
	return nil
}
