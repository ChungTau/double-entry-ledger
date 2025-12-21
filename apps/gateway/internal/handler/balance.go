package handler

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"

	"github.com/chungtau/ledger-gateway/internal/grpcclient"
)

// BalanceHandler handles balance-related endpoints
type BalanceHandler struct {
	ledgerClient grpcclient.LedgerClient
}

// NewBalanceHandler creates a new balance handler
func NewBalanceHandler(ledgerClient grpcclient.LedgerClient) *BalanceHandler {
	return &BalanceHandler{
		ledgerClient: ledgerClient,
	}
}

// BalanceResponse represents the response for getting balance
type BalanceResponse struct {
	AccountID string `json:"account_id"`
	Currency  string `json:"currency"`
	Balance   string `json:"balance"`
	Version   int64  `json:"version"`
}

// Get handles GET /v1/accounts/:id/balance
func (h *BalanceHandler) Get(c *gin.Context) {
	accountID := c.Param("id")

	// Validate UUID format
	if _, err := uuid.Parse(accountID); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    "INVALID_ARGUMENT",
			"message": "Invalid account_id format. Must be a valid UUID.",
		})
		return
	}

	// Call ledger-core gRPC service
	resp, err := h.ledgerClient.GetBalance(c.Request.Context(), accountID)
	if err != nil {
		apiErr := grpcclient.GRPCToHTTPError(err)
		c.JSON(apiErr.HTTPStatus, gin.H{
			"code":    apiErr.Code,
			"message": apiErr.Message,
		})
		return
	}

	c.JSON(http.StatusOK, BalanceResponse{
		AccountID: resp.AccountID,
		Currency:  resp.Currency,
		Balance:   resp.Balance,
		Version:   resp.Version,
	})
}
