package middleware

import (
	"log"
	"net/http"
	"runtime/debug"

	"github.com/gin-gonic/gin"
)

// Recovery middleware recovers from panics and returns a 500 error
func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if err := recover(); err != nil {
				requestID := GetRequestID(c)
				log.Printf("[%s] PANIC recovered: %v\n%s", requestID, err, debug.Stack())

				c.AbortWithStatusJSON(http.StatusInternalServerError, gin.H{
					"code":    "INTERNAL_ERROR",
					"message": "An unexpected error occurred",
				})
			}
		}()

		c.Next()
	}
}
