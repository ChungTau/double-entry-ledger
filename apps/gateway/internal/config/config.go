package config

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	// Server settings
	GatewayPort string

	// gRPC client settings
	GRPCLedgerAddr string
	GRPCTimeout    time.Duration

	// Redis settings
	RedisAddr string

	// JWT settings
	JWTSecret string

	// Rate limiting settings
	RateLimitRPS   int
	RateLimitBurst int

	// Feature flags
	MockMode bool
	DevMode  bool
}

func Load() *Config {
	return &Config{
		GatewayPort:    getEnv("GATEWAY_PORT", "8080"),
		GRPCLedgerAddr: getEnv("GRPC_LEDGER_ADDR", "localhost:9098"),
		GRPCTimeout:    getDurationEnv("GRPC_TIMEOUT_MS", 5000) * time.Millisecond,
		RedisAddr:      getEnv("REDIS_ADDR", "localhost:6379"),
		JWTSecret:      getEnv("JWT_SECRET", "dev-secret-key"),
		RateLimitRPS:   getIntEnv("RATE_LIMIT_RPS", 10),
		RateLimitBurst: getIntEnv("RATE_LIMIT_BURST", 20),
		MockMode:       getBoolEnv("MOCK_MODE", false),
		DevMode:        getBoolEnv("DEV_MODE", false),
	}
}

func getEnv(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok {
		return value
	}
	return fallback
}

func getIntEnv(key string, fallback int) int {
	if value, ok := os.LookupEnv(key); ok {
		if intVal, err := strconv.Atoi(value); err == nil {
			return intVal
		}
	}
	return fallback
}

func getDurationEnv(key string, fallbackMs int) time.Duration {
	if value, ok := os.LookupEnv(key); ok {
		if intVal, err := strconv.Atoi(value); err == nil {
			return time.Duration(intVal)
		}
	}
	return time.Duration(fallbackMs)
}

func getBoolEnv(key string, fallback bool) bool {
	if value, ok := os.LookupEnv(key); ok {
		if boolVal, err := strconv.ParseBool(value); err == nil {
			return boolVal
		}
	}
	return fallback
}
