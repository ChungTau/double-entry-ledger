.PHONY: proto build run test clean

PROTO_DIR := ../../api/proto/v1
GEN_DIR := ./gen/proto/v1

# Generate Go code from proto files
proto:
	@echo "Generating Go code from proto files..."
	@mkdir -p $(GEN_DIR)
	protoc --proto_path=$(PROTO_DIR) \
		--go_out=$(GEN_DIR) --go_opt=paths=source_relative \
		--go-grpc_out=$(GEN_DIR) --go-grpc_opt=paths=source_relative \
		$(PROTO_DIR)/ledger.proto
	@echo "Proto generation complete!"

# Build the gateway binary
build:
	@echo "Building gateway..."
	go build -o bin/gateway ./cmd/main.go
	@echo "Build complete!"

# Run the gateway
run:
	go run ./cmd/main.go

# Run tests
test:
	go test -v ./...

# Clean build artifacts
clean:
	rm -rf bin/
	rm -rf gen/proto/v1/*.go

# Download dependencies
deps:
	go mod download
	go mod tidy

# Generate test JWT token
token:
	go run ./scripts/gen_token.go
