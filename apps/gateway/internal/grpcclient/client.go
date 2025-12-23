package grpcclient

import (
	"context"
	"time"

	pb "github.com/chungtau/ledger-gateway/gen/proto/v1"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/metadata"
)

// LedgerClient defines the interface for interacting with ledger-core service
type LedgerClient interface {
	CreateTransaction(ctx context.Context, req *CreateTransactionRequest) (*TransactionResponse, error)
	GetBalance(ctx context.Context, accountID string) (*BalanceResponse, error)
	CreateAccount(ctx context.Context, req *CreateAccountRequest) (*AccountResponse, error)
	ListAccounts(ctx context.Context, req *ListAccountsRequest) (*ListAccountsResponse, error)
	Close() error
}

// CreateTransactionRequest represents the request to create a transaction
type CreateTransactionRequest struct {
	IdempotencyKey string
	FromAccountID  string
	ToAccountID    string
	Amount         string
	Currency       string
	Description    string
}

// TransactionResponse represents the response from creating a transaction
type TransactionResponse struct {
	TransactionID string
	Status        string
	CreatedAt     string
}

// BalanceResponse represents the response from getting balance
type BalanceResponse struct {
	AccountID string
	Currency  string
	Balance   string
	Version   int64
}

// CreateAccountRequest represents the request to create an account
type CreateAccountRequest struct {
	UserID         string // From JWT - set by handler
	Currency       string // From request body
	InitialBalance string // Optional, from request body
}

// AccountResponse represents the response from account operations
type AccountResponse struct {
	AccountID string
	UserID    string
	Currency  string
	Balance   string
	Version   int64
}

// ListAccountsRequest represents the request to list accounts
type ListAccountsRequest struct {
	UserID   string // From JWT - set by handler
	Page     int32
	PageSize int32
}

// ListAccountsResponse represents the response from listing accounts
type ListAccountsResponse struct {
	Accounts   []AccountResponse
	TotalCount int64 // int64 to match proto definition
	Page       int32
	PageSize   int32
}

// grpcLedgerClient implements LedgerClient using gRPC
type grpcLedgerClient struct {
	conn    *grpc.ClientConn
	client  pb.LedgerServiceClient
	timeout time.Duration
}

// NewGRPCLedgerClient creates a new gRPC client for ledger-core service
func NewGRPCLedgerClient(addr string, timeout time.Duration) (LedgerClient, error) {
	// Configure keepalive parameters for connection stability
	kacp := keepalive.ClientParameters{
		Time:                10 * time.Second, // Send pings every 10 seconds if there is no activity
		Timeout:             time.Second,      // Wait 1 second for ping ack before considering connection dead
		PermitWithoutStream: true,             // Send pings even without active streams
	}

	conn, err := grpc.NewClient(
		addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithKeepaliveParams(kacp),
	)
	if err != nil {
		return nil, err
	}

	return &grpcLedgerClient{
		conn:    conn,
		client:  pb.NewLedgerServiceClient(conn),
		timeout: timeout,
	}, nil
}

// CreateTransaction calls the CreateTransaction RPC
func (c *grpcLedgerClient) CreateTransaction(ctx context.Context, req *CreateTransactionRequest) (*TransactionResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()

	// Forward request ID to gRPC metadata if present
	ctx = forwardRequestID(ctx)

	pbReq := &pb.CreateTransactionRequest{
		IdempotencyKey: req.IdempotencyKey,
		FromAccountId:  req.FromAccountID,
		ToAccountId:    req.ToAccountID,
		Amount:         req.Amount,
		Currency:       req.Currency,
		Description:    req.Description,
	}

	resp, err := c.client.CreateTransaction(ctx, pbReq)
	if err != nil {
		return nil, err
	}

	return &TransactionResponse{
		TransactionID: resp.TransactionId,
		Status:        resp.Status,
		CreatedAt:     resp.CreatedAt,
	}, nil
}

// GetBalance calls the GetBalance RPC
func (c *grpcLedgerClient) GetBalance(ctx context.Context, accountID string) (*BalanceResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()

	// Forward request ID to gRPC metadata if present
	ctx = forwardRequestID(ctx)

	pbReq := &pb.GetBalanceRequest{
		AccountId: accountID,
	}

	resp, err := c.client.GetBalance(ctx, pbReq)
	if err != nil {
		return nil, err
	}

	return &BalanceResponse{
		AccountID: resp.AccountId,
		Currency:  resp.Currency,
		Balance:   resp.Balance,
		Version:   resp.Version,
	}, nil
}

// CreateAccount calls the CreateAccount RPC
func (c *grpcLedgerClient) CreateAccount(ctx context.Context, req *CreateAccountRequest) (*AccountResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()

	// Forward request ID to gRPC metadata if present
	ctx = forwardRequestID(ctx)

	pbReq := &pb.CreateAccountRequest{
		UserId:         req.UserID,
		Currency:       req.Currency,
		InitialBalance: req.InitialBalance,
	}

	resp, err := c.client.CreateAccount(ctx, pbReq)
	if err != nil {
		return nil, err
	}

	return &AccountResponse{
		AccountID: resp.AccountId,
		UserID:    resp.UserId,
		Currency:  resp.Currency,
		Balance:   resp.Balance,
		Version:   resp.Version,
	}, nil
}

// ListAccounts calls the ListAccounts RPC
func (c *grpcLedgerClient) ListAccounts(ctx context.Context, req *ListAccountsRequest) (*ListAccountsResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, c.timeout)
	defer cancel()

	// Forward request ID to gRPC metadata if present
	ctx = forwardRequestID(ctx)

	pbReq := &pb.ListAccountsRequest{
		UserId:   req.UserID,
		Page:     req.Page,
		PageSize: req.PageSize,
	}

	resp, err := c.client.ListAccounts(ctx, pbReq)
	if err != nil {
		return nil, err
	}

	accounts := make([]AccountResponse, 0, len(resp.Accounts))
	for _, acc := range resp.Accounts {
		accounts = append(accounts, AccountResponse{
			AccountID: acc.AccountId,
			UserID:    acc.UserId,
			Currency:  acc.Currency,
			Balance:   acc.Balance,
			Version:   acc.Version,
		})
	}

	return &ListAccountsResponse{
		Accounts:   accounts,
		TotalCount: resp.TotalCount,
		Page:       resp.Page,
		PageSize:   resp.PageSize,
	}, nil
}

// Close closes the gRPC connection
func (c *grpcLedgerClient) Close() error {
	return c.conn.Close()
}

// forwardRequestID extracts request ID from context and adds it to gRPC metadata
func forwardRequestID(ctx context.Context) context.Context {
	if requestID, ok := ctx.Value("request_id").(string); ok && requestID != "" {
		md := metadata.Pairs("x-request-id", requestID)
		ctx = metadata.NewOutgoingContext(ctx, md)
	}
	return ctx
}
