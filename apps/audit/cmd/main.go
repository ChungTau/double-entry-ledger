package main

import (
	"context"
	"encoding/json"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/chungtau/ledger-audit/internal/model"
	"github.com/segmentio/kafka-go"
)

func main() {
	brokerAddress := getEnv("KAFKA_BROKER", "localhost:9092")
	topic := getEnv("KAFKA_TOPIC", "transaction-events")
	groupID := "audit-service-group"

	log.Printf("Starting Audit Service. Broker: %s, Topic: %s", brokerAddress, topic)

	r := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  []string{brokerAddress},
		Topic:    topic,
		GroupID:  groupID, // Load Balancing
		MinBytes: 1,       // 1 byte - consume messages immediately
		MaxBytes: 10e6,    // 10MB
	})

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		sigChan := make(chan os.Signal, 1)
		signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
		<-sigChan
		log.Println("Received shutdown signal, stopping consumer...")
		cancel()
	}()

	// Consumer Loop
	log.Println("Consumer started, waiting for messages...")
	for {
		m, err := r.ReadMessage(ctx)
		if err != nil {
			if ctx.Err() != nil {
				// Context canceled, exit loop normally
				break
			}
			log.Printf("Error reading message: %v", err)
			continue
		}

		processMessage(m)
	}

	if err := r.Close(); err != nil {
		log.Printf("failed to close reader: %v", err)
	}
	log.Println("Audit Service stopped gracefully.")
}

func processMessage(m kafka.Message) {
	log.Printf("Received Event | Key: %s | Partition: %d | Offset: %d", string(m.Key), m.Partition, m.Offset)

	var event model.TransactionCreatedEvent
	err := json.Unmarshal(m.Value, &event)
	if err != nil {
		log.Printf("❌ Failed to unmarshal JSON: %v. Raw: %s", err, string(m.Value))
		return
	}

	log.Printf("✅ AUDIT LOG: Transaction [%s] created. Amount: %s %s. Status: %s",
		event.TransactionID, event.Amount, event.Currency, event.Status)
}

// Helper to read env vars
func getEnv(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok {
		return value
	}
	return fallback
}
