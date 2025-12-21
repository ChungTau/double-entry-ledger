package main

import (
	"log"

	"github.com/chungtau/ledger-gateway/internal/config"
	"github.com/chungtau/ledger-gateway/internal/server"
)

func main() {
	log.Println("Loading configuration...")
	cfg := config.Load()

	log.Println("Initializing server...")
	srv, err := server.New(cfg)
	if err != nil {
		log.Fatalf("Failed to create server: %v", err)
	}

	if err := srv.Run(); err != nil {
		log.Fatalf("Server error: %v", err)
	}
}
