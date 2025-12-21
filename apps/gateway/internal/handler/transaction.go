package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/chungtau/ledger-gateway/internal/grpcclient"
)

// TransactionHandler handles transaction-related endpoints
type TransactionHandler struct {
	ledgerClient grpcclient.LedgerClient
}

// NewTransactionHandler creates a new transaction handler
func NewTransactionHandler(ledgerClient grpcclient.LedgerClient) *TransactionHandler {
	return &TransactionHandler{
		ledgerClient: ledgerClient,
	}
}

// CreateTransactionRequest represents the request body for creating a transaction
type CreateTransactionRequest struct {
	IdempotencyKey string `json:"idempotency_key" binding:"required"`
	FromAccountID  string `json:"from_account_id" binding:"required"`
	ToAccountID    string `json:"to_account_id" binding:"required"`
	Amount         string `json:"amount" binding:"required"`
	Currency       string `json:"currency" binding:"required,len=3"`
	Description    string `json:"description"`
}

// CreateTransactionResponse represents the response for creating a transaction
type CreateTransactionResponse struct {
	TransactionID string `json:"transaction_id"`
	Status        string `json:"status"`
	CreatedAt     string `json:"created_at"`
}

// Create handles POST /v1/transactions
func (h *TransactionHandler) Create(c *gin.Context) {
	var req CreateTransactionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    "INVALID_REQUEST",
			"message": "Invalid request body: " + err.Error(),
		})
		return
	}

	// Validate UUIDs
	if _, err := uuid.Parse(req.IdempotencyKey); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    "INVALID_ARGUMENT",
			"message": "Invalid idempotency_key format. Must be a valid UUID.",
		})
		return
	}

	if _, err := uuid.Parse(req.FromAccountID); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    "INVALID_ARGUMENT",
			"message": "Invalid from_account_id format. Must be a valid UUID.",
		})
		return
	}

	if _, err := uuid.Parse(req.ToAccountID); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    "INVALID_ARGUMENT",
			"message": "Invalid to_account_id format. Must be a valid UUID.",
		})
		return
	}

	// Call ledger-core gRPC service
	grpcReq := &grpcclient.CreateTransactionRequest{
		IdempotencyKey: req.IdempotencyKey,
		FromAccountID:  req.FromAccountID,
		ToAccountID:    req.ToAccountID,
		Amount:         req.Amount,
		Currency:       req.Currency,
		Description:    req.Description,
	}

	resp, err := h.ledgerClient.CreateTransaction(c.Request.Context(), grpcReq)
	if err != nil {
		apiErr := grpcclient.GRPCToHTTPError(err)
		c.JSON(apiErr.HTTPStatus, gin.H{
			"code":    apiErr.Code,
			"message": apiErr.Message,
		})
		return
	}

	c.JSON(http.StatusCreated, CreateTransactionResponse{
		TransactionID: resp.TransactionID,
		Status:        resp.Status,
		CreatedAt:     resp.CreatedAt,
	})
}
