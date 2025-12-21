# Stage 1: Build
FROM golang:1.25-alpine AS builder

WORKDIR /app

# Copy go.mod and go.sum
COPY go.mod go.sum ./
RUN go mod download

# Copy source code (including generated proto files)
COPY . .

# Build the binary
RUN CGO_ENABLED=0 GOOS=linux go build -o gateway-service ./cmd/main.go

# Stage 2: Run
FROM alpine:latest

WORKDIR /app

# Copy binary from builder
COPY --from=builder /app/gateway-service .

# Expose HTTP port
EXPOSE 8081

# Run
CMD ["./gateway-service"]
