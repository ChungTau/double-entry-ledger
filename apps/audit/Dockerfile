# Stage 1: Build
FROM golang:1.25-alpine AS builder

WORKDIR /app

# Copy go.mod and go.sum from audit directory
COPY apps/audit/go.mod apps/audit/go.sum ./apps/audit/

# Download dependencies
WORKDIR /app/apps/audit
RUN go mod download

# Copy proto definitions (if needed for generated code)
COPY api/proto /app/api/proto

# Copy audit source code
COPY apps/audit /app/apps/audit

# Build the binary
RUN CGO_ENABLED=0 GOOS=linux go build -o /app/audit-service ./cmd/main.go

# Stage 2: Run
FROM alpine:latest

RUN apk --no-cache add ca-certificates

WORKDIR /app

# Copy binary from builder
COPY --from=builder /app/audit-service .

# Expose HTTP port (if applicable)
EXPOSE 8082

# Run
CMD ["./audit-service"]
