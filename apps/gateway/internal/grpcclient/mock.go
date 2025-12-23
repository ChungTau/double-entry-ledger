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
	UserID   string
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
		UserID:   "test-user-1",
		Currency: "USD",
		Balance:  "10000.00",
		Version:  1,
	}
	client.accounts["22222222-2222-2222-2222-222222222222"] = &mockAccount{
		ID:       "22222222-2222-2222-2222-222222222222",
		UserID:   "test-user-1",
		Currency: "USD",
		Balance:  "5000.00",
		Version:  1,
	}
	client.accounts["33333333-3333-3333-3333-333333333333"] = &mockAccount{
		ID:       "33333333-3333-3333-3333-333333333333",
		UserID:   "test-user-2",
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

func (c *mockLedgerClient) CreateAccount(ctx context.Context, req *CreateAccountRequest) (*AccountResponse, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	// Validate required fields
	if req.UserID == "" {
		return nil, fmt.Errorf("user_id is required")
	}
	if len(req.Currency) != 3 {
		return nil, fmt.Errorf("currency must be exactly 3 characters")
	}

	// Default initial balance to "0" if not provided
	balance := req.InitialBalance
	if balance == "" {
		balance = "0"
	}

	// Create new account
	accountID := uuid.New().String()
	acc := &mockAccount{
		ID:       accountID,
		UserID:   req.UserID,
		Currency: req.Currency,
		Balance:  balance,
		Version:  0,
	}
	c.accounts[accountID] = acc

	return &AccountResponse{
		AccountID: acc.ID,
		UserID:    acc.UserID,
		Currency:  acc.Currency,
		Balance:   acc.Balance,
		Version:   acc.Version,
	}, nil
}

func (c *mockLedgerClient) ListAccounts(ctx context.Context, req *ListAccountsRequest) (*ListAccountsResponse, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	// Validate required fields
	if req.UserID == "" {
		return nil, fmt.Errorf("user_id is required")
	}

	// Default pagination
	page := req.Page
	pageSize := req.PageSize
	if pageSize <= 0 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}

	// Filter accounts by user ID
	var userAccounts []AccountResponse
	for _, acc := range c.accounts {
		if acc.UserID == req.UserID {
			userAccounts = append(userAccounts, AccountResponse{
				AccountID: acc.ID,
				UserID:    acc.UserID,
				Currency:  acc.Currency,
				Balance:   acc.Balance,
				Version:   acc.Version,
			})
		}
	}

	// Apply pagination
	totalCount := int64(len(userAccounts))
	start := int(page) * int(pageSize)
	end := start + int(pageSize)

	if start >= len(userAccounts) {
		return &ListAccountsResponse{
			Accounts:   []AccountResponse{},
			TotalCount: totalCount,
			Page:       page,
			PageSize:   pageSize,
		}, nil
	}

	if end > len(userAccounts) {
		end = len(userAccounts)
	}

	return &ListAccountsResponse{
		Accounts:   userAccounts[start:end],
		TotalCount: totalCount,
		Page:       page,
		PageSize:   pageSize,
	}, nil
}

func (c *mockLedgerClient) Close() error {
	return nil
}
