package main

import (
	"context"
	"encoding/json"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/chungtau/ledger-audit/internal/dlq"
	"github.com/chungtau/ledger-audit/internal/elasticsearch"
	"github.com/chungtau/ledger-audit/internal/model"
	"github.com/segmentio/kafka-go"
)

var esClient *elasticsearch.Client
var dlqProducer *dlq.Producer

func main() {
	brokerAddress := getEnv("KAFKA_BROKER", "localhost:9092")
	topic := getEnv("KAFKA_TOPIC", "transaction-events")
	dlqTopic := getEnv("KAFKA_DLQ_TOPIC", "transactions-dlq")
	groupID := "audit-service-group"

	esURL := getEnv("ELASTICSEARCH_URL", "http://localhost:9200")
	esIndex := getEnv("ELASTICSEARCH_INDEX", "transactions")
	esUsername := getEnv("ELASTICSEARCH_USERNAME", "")
	esPassword := getEnv("ELASTICSEARCH_PASSWORD", "")
	esSkipTLS := getEnv("ELASTICSEARCH_SKIP_TLS_VERIFY", "false") == "true"

	log.Printf("Starting Audit Service. Broker: %s, Topic: %s", brokerAddress, topic)
	log.Printf("Elasticsearch: %s, Index: %s", esURL, esIndex)
	log.Printf("DLQ Topic: %s", dlqTopic)

	// Initialize DLQ Producer
	dlqProducer = dlq.NewProducer([]string{brokerAddress}, dlqTopic)

	// Initialize Elasticsearch client with retry
	var err error
	for i := 0; i < 10; i++ {
		esClient, err = elasticsearch.NewClient(elasticsearch.Config{
			URL:           esURL,
			Index:         esIndex,
			Username:      esUsername,
			Password:      esPassword,
			SkipTLSVerify: esSkipTLS,
			DLQProducer:   dlqProducer,
		})
		if err == nil {
			break
		}
		log.Printf("Failed to connect to Elasticsearch (attempt %d/10): %v", i+1, err)
		time.Sleep(5 * time.Second)
	}
	if err != nil {
		log.Fatalf("Failed to initialize Elasticsearch client after 10 attempts: %v", err)
	}

	r := kafka.NewReader(kafka.ReaderConfig{
		Brokers:  []string{brokerAddress},
		Topic:    topic,
		GroupID:  groupID,
		MinBytes: 1,
		MaxBytes: 10e6,
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

		processMessage(ctx, m)
	}

	// Graceful shutdown: flush bulk indexer before closing
	log.Println("Flushing Elasticsearch bulk indexer...")
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer shutdownCancel()
	if err := esClient.Close(shutdownCtx); err != nil {
		log.Printf("Error closing Elasticsearch client: %v", err)
	}

	if err := r.Close(); err != nil {
		log.Printf("Failed to close reader: %v", err)
	}

	// Close DLQ Producer
	if dlqProducer != nil {
		if err := dlqProducer.Close(); err != nil {
			log.Printf("Failed to close DLQ producer: %v", err)
		}
	}

	log.Println("Audit Service stopped gracefully.")
}

func processMessage(ctx context.Context, m kafka.Message) {
	log.Printf("Received Event | Key: %s | Partition: %d | Offset: %d", string(m.Key), m.Partition, m.Offset)

	var event model.TransactionCreatedEvent
	err := json.Unmarshal(m.Value, &event)
	if err != nil {
		log.Printf("ERROR: Failed to unmarshal JSON: %v. Raw: %s", err, string(m.Value))
		return
	}

	// Existing logging functionality preserved
	log.Printf("AUDIT LOG: Transaction [%s] created. Amount: %s %s. Status: %s",
		event.TransactionID, event.Amount, event.Currency, event.Status)

	// Index to Elasticsearch
	doc := elasticsearch.TransactionDocument{
		TransactionID:  event.TransactionID,
		IdempotencyKey: event.IdempotencyKey,
		FromAccountID:  event.FromAccountID,
		ToAccountID:    event.ToAccountID,
		AmountRaw:      event.Amount,
		Currency:       event.Currency,
		Status:         event.Status,
		BookedAt:       event.BookedAt,
	}

	if err := esClient.IndexTransaction(ctx, doc, m.Value); err != nil {
		// Error already logged in IndexTransaction with full payload
		return
	}
}

// Helper to read env vars
func getEnv(key, fallback string) string {
	if value, ok := os.LookupEnv(key); ok {
		return value
	}
	return fallback
}
