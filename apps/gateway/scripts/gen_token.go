package main

import (
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
)

func main() {
	// Command line flags
	userID := flag.String("user", "", "User ID (default: generate new UUID)")
	secret := flag.String("secret", "dev-secret-key", "JWT secret key")
	expiry := flag.Int("expiry", 3600, "Token expiry in seconds (default: 3600)")
	flag.Parse()

	// Generate user ID if not provided
	sub := *userID
	if sub == "" {
		sub = uuid.New().String()
	}

	expiresAt := time.Now().Add(time.Duration(*expiry) * time.Second)

	// Create JWT claims
	claims := jwt.MapClaims{
		"sub": sub,
		"iat": time.Now().Unix(),
		"exp": expiresAt.Unix(),
		"nbf": time.Now().Unix(),
	}

	// Create and sign token
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tokenString, err := token.SignedString([]byte(*secret))
	if err != nil {
		fmt.Fprintf(os.Stderr, "Error generating token: %v\n", err)
		os.Exit(1)
	}

	fmt.Println("=== Generated JWT Token ===")
	fmt.Printf("User ID:    %s\n", sub)
	fmt.Printf("Expires At: %s\n", expiresAt.UTC().Format(time.RFC3339))
	fmt.Printf("Secret:     %s\n", *secret)
	fmt.Println("")
	fmt.Println("Token:")
	fmt.Println(tokenString)
	fmt.Println("")
	fmt.Println("Usage:")
	fmt.Printf("curl -H \"Authorization: Bearer %s\" http://localhost:8080/v1/accounts/...\n", tokenString)
}
