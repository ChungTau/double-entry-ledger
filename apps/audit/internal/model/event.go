package model

type TransactionCreatedEvent struct {
	TransactionID  string `json:"transactionId"`
	IdempotencyKey string `json:"idempotencyKey"`
	FromAccountID  string `json:"fromAccountId"`
	ToAccountID    string `json:"toAccountId"`
	Amount         string `json:"amount"`
	Currency       string `json:"currency"`
	Status         string `json:"status"`
	BookedAt       string `json:"bookedAt"`
}
