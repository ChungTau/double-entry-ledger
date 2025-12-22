# Stage 1: Build
FROM golang:1.25-alpine AS builder

WORKDIR /app

# Copy go.mod and go.sum from gateway directory
COPY apps/gateway/go.mod apps/gateway/go.sum ./apps/gateway/

# Download dependencies
WORKDIR /app/apps/gateway
RUN go mod download

# Copy proto definitions (if needed for generated code)
COPY api/proto /app/api/proto

# Copy gateway source code
COPY apps/gateway /app/apps/gateway

# Build the binary
RUN CGO_ENABLED=0 GOOS=linux go build -o /app/gateway-service ./cmd/main.go

# Stage 2: Run
FROM alpine:latest

RUN apk --no-cache add ca-certificates

WORKDIR /app

# Copy binary from builder
COPY --from=builder /app/gateway-service .

# Expose HTTP port
EXPOSE 8081

# Run
CMD ["./gateway-service"]
