package handler

import (
	"net/http"
	"strconv"

	"github.com/gin-gonic/gin"

	"github.com/chungtau/ledger-gateway/internal/grpcclient"
	"github.com/chungtau/ledger-gateway/internal/middleware"
)

// AccountHandler handles account-related endpoints
type AccountHandler struct {
	ledgerClient grpcclient.LedgerClient
}

// NewAccountHandler creates a new account handler
func NewAccountHandler(ledgerClient grpcclient.LedgerClient) *AccountHandler {
	return &AccountHandler{
		ledgerClient: ledgerClient,
	}
}

// CreateAccountRequest represents the request body for creating an account
type CreateAccountRequest struct {
	Currency       string `json:"currency" binding:"required,len=3"`
	InitialBalance string `json:"initial_balance"`
}

// AccountResponse represents the response for account operations
type AccountResponse struct {
	AccountID string `json:"account_id"`
	UserID    string `json:"user_id"`
	Currency  string `json:"currency"`
	Balance   string `json:"balance"`
	Version   int64  `json:"version"`
}

// ListAccountsResponse represents the response for listing accounts
type ListAccountsResponse struct {
	Accounts   []AccountResponse `json:"accounts"`
	TotalCount int64             `json:"total_count"`
	Page       int32             `json:"page"`
	PageSize   int32             `json:"page_size"`
}

// Create handles POST /v1/accounts
func (h *AccountHandler) Create(c *gin.Context) {
	var req CreateAccountRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    "INVALID_REQUEST",
			"message": "Invalid request body: " + err.Error(),
		})
		return
	}

	// Get user ID from JWT token (set by auth middleware)
	userID := middleware.GetUserID(c)
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{
			"code":    "UNAUTHORIZED",
			"message": "User ID not found in token",
		})
		return
	}

	// Call ledger-core gRPC service
	grpcReq := &grpcclient.CreateAccountRequest{
		UserID:         userID,
		Currency:       req.Currency,
		InitialBalance: req.InitialBalance,
	}

	resp, err := h.ledgerClient.CreateAccount(c.Request.Context(), grpcReq)
	if err != nil {
		apiErr := grpcclient.GRPCToHTTPError(err)
		c.JSON(apiErr.HTTPStatus, gin.H{
			"code":    apiErr.Code,
			"message": apiErr.Message,
		})
		return
	}

	c.JSON(http.StatusCreated, AccountResponse{
		AccountID: resp.AccountID,
		UserID:    resp.UserID,
		Currency:  resp.Currency,
		Balance:   resp.Balance,
		Version:   resp.Version,
	})
}

// List handles GET /v1/accounts
func (h *AccountHandler) List(c *gin.Context) {
	// Get user ID from JWT token (set by auth middleware)
	userID := middleware.GetUserID(c)
	if userID == "" {
		c.JSON(http.StatusUnauthorized, gin.H{
			"code":    "UNAUTHORIZED",
			"message": "User ID not found in token",
		})
		return
	}

	// Parse pagination query parameters
	page := int32(0)
	pageSize := int32(20) // Default page size

	if pageStr := c.Query("page"); pageStr != "" {
		if p, err := strconv.ParseInt(pageStr, 10, 32); err == nil && p >= 0 {
			page = int32(p)
		}
	}

	if pageSizeStr := c.Query("page_size"); pageSizeStr != "" {
		if ps, err := strconv.ParseInt(pageSizeStr, 10, 32); err == nil && ps > 0 {
			pageSize = int32(ps)
		}
	}

	// Call ledger-core gRPC service
	grpcReq := &grpcclient.ListAccountsRequest{
		UserID:   userID,
		Page:     page,
		PageSize: pageSize,
	}

	resp, err := h.ledgerClient.ListAccounts(c.Request.Context(), grpcReq)
	if err != nil {
		apiErr := grpcclient.GRPCToHTTPError(err)
		c.JSON(apiErr.HTTPStatus, gin.H{
			"code":    apiErr.Code,
			"message": apiErr.Message,
		})
		return
	}

	// Convert gRPC response to HTTP response
	accounts := make([]AccountResponse, 0, len(resp.Accounts))
	for _, acc := range resp.Accounts {
		accounts = append(accounts, AccountResponse{
			AccountID: acc.AccountID,
			UserID:    acc.UserID,
			Currency:  acc.Currency,
			Balance:   acc.Balance,
			Version:   acc.Version,
		})
	}

	c.JSON(http.StatusOK, ListAccountsResponse{
		Accounts:   accounts,
		TotalCount: resp.TotalCount,
		Page:       resp.Page,
		PageSize:   resp.PageSize,
	})
}
