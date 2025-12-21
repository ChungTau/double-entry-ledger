package grpcclient

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
)

// mockLedgerClient implements LedgerClient for testing/development without ledger-core
type mockLedgerClient struct {
	mu       sync.RWMutex
	accounts map[string]*mockAccount
	txns     map[string]*TransactionResponse
}

type mockAccount struct {
	ID       string
	Currency string
	Balance  string
	Version  int64
}

// NewMockLedgerClient creates a mock client for development/testing
func NewMockLedgerClient() LedgerClient {
	client := &mockLedgerClient{
		accounts: make(map[string]*mockAccount),
		txns:     make(map[string]*TransactionResponse),
	}

	// Initialize with some test accounts
	client.accounts["11111111-1111-1111-1111-111111111111"] = &mockAccount{
		ID:       "11111111-1111-1111-1111-111111111111",
		Currency: "USD",
		Balance:  "10000.00",
		Version:  1,
	}
	client.accounts["22222222-2222-2222-2222-222222222222"] = &mockAccount{
		ID:       "22222222-2222-2222-2222-222222222222",
		Currency: "USD",
		Balance:  "5000.00",
		Version:  1,
	}
	client.accounts["33333333-3333-3333-3333-333333333333"] = &mockAccount{
		ID:       "33333333-3333-3333-3333-333333333333",
		Currency: "HKD",
		Balance:  "50000.00",
		Version:  1,
	}

	return client
}

func (c *mockLedgerClient) CreateTransaction(ctx context.Context, req *CreateTransactionRequest) (*TransactionResponse, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	// Check for duplicate idempotency key
	if existing, ok := c.txns[req.IdempotencyKey]; ok {
		return existing, nil
	}

	// Validate accounts exist
	fromAcc, ok := c.accounts[req.FromAccountID]
	if !ok {
		return nil, fmt.Errorf("source account not found: %s", req.FromAccountID)
	}

	toAcc, ok := c.accounts[req.ToAccountID]
	if !ok {
		return nil, fmt.Errorf("destination account not found: %s", req.ToAccountID)
	}

	// Validate currency matches
	if fromAcc.Currency != req.Currency || toAcc.Currency != req.Currency {
		return nil, fmt.Errorf("currency mismatch")
	}

	// Create mock transaction response
	resp := &TransactionResponse{
		TransactionID: uuid.New().String(),
		Status:        "BOOKED",
		CreatedAt:     time.Now().UTC().Format(time.RFC3339),
	}

	// Store for idempotency
	c.txns[req.IdempotencyKey] = resp

	return resp, nil
}

func (c *mockLedgerClient) GetBalance(ctx context.Context, accountID string) (*BalanceResponse, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	acc, ok := c.accounts[accountID]
	if !ok {
		return nil, fmt.Errorf("account not found: %s", accountID)
	}

	return &BalanceResponse{
		AccountID: acc.ID,
		Currency:  acc.Currency,
		Balance:   acc.Balance,
		Version:   acc.Version,
	}, nil
}

func (c *mockLedgerClient) Close() error {
	return nil
}
