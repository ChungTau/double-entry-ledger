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
