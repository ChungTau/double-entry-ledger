package elasticsearch

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"strconv"
	"strings"
	"time"

	"github.com/chungtau/ledger-audit/internal/dlq"
	"github.com/elastic/go-elasticsearch/v8"
	"github.com/elastic/go-elasticsearch/v8/esutil"
)

// Client wraps the Elasticsearch client with audit-specific functionality
type Client struct {
	es          *elasticsearch.Client
	indexer     esutil.BulkIndexer
	index       string
	dlqProducer *dlq.Producer
}

// Config holds Elasticsearch connection configuration
type Config struct {
	URL         string
	Index       string
	DLQProducer *dlq.Producer
}

// TransactionDocument represents the document to be indexed
type TransactionDocument struct {
	TransactionID  string    `json:"transactionId"`
	IdempotencyKey string    `json:"idempotencyKey"`
	FromAccountID  string    `json:"fromAccountId"`
	ToAccountID    string    `json:"toAccountId"`
	Amount         float64   `json:"amount"`
	AmountRaw      string    `json:"amountRaw"`
	Currency       string    `json:"currency"`
	Status         string    `json:"status"`
	BookedAt       string    `json:"bookedAt"`
	IndexedAt      time.Time `json:"indexedAt"`
}

// Index mapping for transactions
const indexMapping = `{
	"settings": {
		"number_of_shards": 1,
		"number_of_replicas": 0,
		"index": {
			"refresh_interval": "1s"
		}
	},
	"mappings": {
		"properties": {
			"transactionId": { "type": "keyword" },
			"idempotencyKey": { "type": "keyword" },
			"fromAccountId": { "type": "keyword" },
			"toAccountId": { "type": "keyword" },
			"amount": { "type": "scaled_float", "scaling_factor": 10000 },
			"amountRaw": { "type": "keyword" },
			"currency": { "type": "keyword" },
			"status": { "type": "keyword" },
			"bookedAt": { "type": "date", "format": "strict_date_optional_time||epoch_millis" },
			"indexedAt": { "type": "date" }
		}
	}
}`

// NewClient creates a new Elasticsearch client wrapper
func NewClient(cfg Config) (*Client, error) {
	esCfg := elasticsearch.Config{
		Addresses: []string{cfg.URL},
		RetryOnStatus: []int{502, 503, 504, 429},
		MaxRetries:    3,
	}

	es, err := elasticsearch.NewClient(esCfg)
	if err != nil {
		return nil, fmt.Errorf("failed to create Elasticsearch client: %w", err)
	}

	// Verify connection
	res, err := es.Info()
	if err != nil {
		return nil, fmt.Errorf("failed to connect to Elasticsearch: %w", err)
	}
	defer res.Body.Close()

	if res.IsError() {
		return nil, fmt.Errorf("Elasticsearch returned error: %s", res.Status())
	}

	log.Printf("Connected to Elasticsearch: %s", res.Status())

	client := &Client{
		es:          es,
		index:       cfg.Index,
		dlqProducer: cfg.DLQProducer,
	}

	// Ensure index exists with proper mapping
	if err := client.ensureIndex(); err != nil {
		return nil, fmt.Errorf("failed to ensure index: %w", err)
	}

	// Create bulk indexer
	indexer, err := esutil.NewBulkIndexer(esutil.BulkIndexerConfig{
		Client:        es,
		Index:         cfg.Index,
		NumWorkers:    2,
		FlushBytes:    5e+6, // 5MB
		FlushInterval: 30 * time.Second,
		OnError: func(ctx context.Context, err error) {
			log.Printf("ERROR: Bulk indexer error: %v", err)
		},
	})
	if err != nil {
		return nil, fmt.Errorf("failed to create bulk indexer: %w", err)
	}

	client.indexer = indexer

	return client, nil
}

// ensureIndex creates the index with mapping if it doesn't exist
func (c *Client) ensureIndex() error {
	res, err := c.es.Indices.Exists([]string{c.index})
	if err != nil {
		return fmt.Errorf("failed to check index existence: %w", err)
	}
	defer res.Body.Close()

	// Index exists
	if res.StatusCode == 200 {
		log.Printf("Index '%s' already exists", c.index)
		return nil
	}

	// Create index with mapping
	res, err = c.es.Indices.Create(
		c.index,
		c.es.Indices.Create.WithBody(strings.NewReader(indexMapping)),
	)
	if err != nil {
		return fmt.Errorf("failed to create index: %w", err)
	}
	defer res.Body.Close()

	if res.IsError() {
		return fmt.Errorf("failed to create index: %s", res.Status())
	}

	log.Printf("Created index '%s' with mapping", c.index)
	return nil
}

// IndexTransaction adds a transaction document to the bulk indexer
func (c *Client) IndexTransaction(ctx context.Context, doc TransactionDocument, rawJSON []byte) error {
	// Add indexing timestamp
	doc.IndexedAt = time.Now().UTC()

	// Parse amount from string to float64
	if amount, err := strconv.ParseFloat(doc.AmountRaw, 64); err == nil {
		doc.Amount = amount
	}

	// Serialize document
	body, err := json.Marshal(doc)
	if err != nil {
		log.Printf("ERROR: Failed to marshal document. Raw payload: %s", string(rawJSON))
		return fmt.Errorf("failed to marshal document: %w", err)
	}

	// Add to bulk indexer
	err = c.indexer.Add(
		ctx,
		esutil.BulkIndexerItem{
			Action:     "index",
			DocumentID: doc.TransactionID,
			Body:       bytes.NewReader(body),
			OnSuccess: func(ctx context.Context, item esutil.BulkIndexerItem, res esutil.BulkIndexerResponseItem) {
				log.Printf("Indexed transaction [%s] to Elasticsearch", doc.TransactionID)
			},
			OnFailure: func(ctx context.Context, item esutil.BulkIndexerItem, res esutil.BulkIndexerResponseItem, err error) {
				var errorType, errorReason string
				if err != nil {
					errorType = "client_error"
					errorReason = err.Error()
					log.Printf("ERROR: Failed to index transaction [%s]: %v. Raw payload: %s", doc.TransactionID, err, string(rawJSON))
				} else {
					errorType = res.Error.Type
					errorReason = res.Error.Reason
					log.Printf("ERROR: Failed to index transaction [%s]: %s %s. Raw payload: %s", doc.TransactionID, res.Error.Type, res.Error.Reason, string(rawJSON))
				}

				// Send to DLQ if producer is configured
				if c.dlqProducer != nil {
					dlqDoc := dlq.FailedDocument{
						OriginalDocument: rawJSON,
						DocumentID:       doc.TransactionID,
						ErrorType:        errorType,
						ErrorReason:      errorReason,
						FailedAt:         time.Now().UTC(),
						RetryCount:       0,
						SourceTopic:      "transaction-events",
					}
					// Use independent context to ensure DLQ write even if parent context is cancelled
					timeoutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
					defer cancel()
					if dlqErr := c.dlqProducer.SendToDeadLetter(timeoutCtx, dlqDoc); dlqErr != nil {
						log.Printf("ERROR: Failed to send to DLQ: %v. Original payload: %s", dlqErr, string(rawJSON))
					}
				}
			},
		},
	)
	if err != nil {
		log.Printf("ERROR: Failed to add transaction to bulk indexer. Raw payload: %s", string(rawJSON))
		return fmt.Errorf("failed to add to bulk indexer: %w", err)
	}

	return nil
}

// Close flushes and closes the bulk indexer
func (c *Client) Close(ctx context.Context) error {
	if c.indexer != nil {
		if err := c.indexer.Close(ctx); err != nil {
			return fmt.Errorf("failed to close bulk indexer: %w", err)
		}
		stats := c.indexer.Stats()
		log.Printf("Bulk indexer closed. Flushed: %d, Failed: %d", stats.NumFlushed, stats.NumFailed)
	}
	return nil
}

// Stats returns the bulk indexer statistics
func (c *Client) Stats() esutil.BulkIndexerStats {
	return c.indexer.Stats()
}
