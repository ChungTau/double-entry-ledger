package dlq

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"github.com/segmentio/kafka-go"
)

// FailedDocument represents a document that failed ES indexing
type FailedDocument struct {
	OriginalDocument json.RawMessage `json:"originalDocument"`
	DocumentID       string          `json:"documentId"`
	ErrorType        string          `json:"errorType"`
	ErrorReason      string          `json:"errorReason"`
	FailedAt         time.Time       `json:"failedAt"`
	RetryCount       int             `json:"retryCount"`
	SourceTopic      string          `json:"sourceTopic"`
}

// Producer wraps Kafka writer for DLQ operations
type Producer struct {
	writer *kafka.Writer
	topic  string
}

// NewProducer creates a new DLQ producer
func NewProducer(brokers []string, topic string) *Producer {
	writer := &kafka.Writer{
		Addr:         kafka.TCP(brokers...),
		Topic:        topic,
		Balancer:     &kafka.LeastBytes{},
		BatchSize:    100,
		BatchTimeout: 10 * time.Millisecond,
		RequiredAcks: kafka.RequireOne,
		Async:        false, // Sync writes for reliability
	}

	log.Printf("DLQ Producer initialized for topic: %s", topic)
	return &Producer{
		writer: writer,
		topic:  topic,
	}
}

// SendToDeadLetter sends a failed document to the DLQ
func (p *Producer) SendToDeadLetter(ctx context.Context, doc FailedDocument) error {
	payload, err := json.Marshal(doc)
	if err != nil {
		return err
	}

	// Use DocumentID as Key to ensure same transaction failures go to same partition
	err = p.writer.WriteMessages(ctx, kafka.Message{
		Key:   []byte(doc.DocumentID),
		Value: payload,
	})

	if err != nil {
		log.Printf("ERROR: Failed to send document [%s] to DLQ: %v", doc.DocumentID, err)
		return err
	}

	log.Printf("Sent failed document [%s] to DLQ topic [%s]", doc.DocumentID, p.topic)
	return nil
}

// Close closes the Kafka writer
func (p *Producer) Close() error {
	if p.writer != nil {
		return p.writer.Close()
	}
	return nil
}
