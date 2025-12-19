package com.chungtau.ledger_core.fixtures;

import com.chungtau.ledger.grpc.v1.CreateTransactionRequest;
import com.chungtau.ledger_core.entity.Account;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Test Data Builder for creating test entities and gRPC requests.
 * Provides fluent API for constructing test data with sensible defaults.
 *
 * Usage:
 * <pre>
 * // Method 1: Build from scratch
 * Account account = TestDataBuilder.anAccount()
 *     .userId("test-user")
 *     .balance("100.00")
 *     .currency("HKD")
 *     .build();
 *
 * // Method 2: Use default values + modify only needed fields (Recommended)
 * Account account = TestDataBuilder.anAccount()
 *     .withDefaultValues()
 *     .balance("0.01")  // Only modify balance
 *     .build();
 * </pre>
 */
public class TestDataBuilder {

    // ========== Account Builder ==========

    public static class AccountBuilder {
        private UUID id = null;
        private String userId = "test-user-" + UUID.randomUUID();
        private BigDecimal balance = new BigDecimal("1000.0000");
        private String currency = "HKD";
        private Long version = 0L;

        public AccountBuilder id(UUID id) {
            this.id = id;
            return this;
        }

        public AccountBuilder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public AccountBuilder balance(String balance) {
            this.balance = new BigDecimal(balance);
            return this;
        }

        public AccountBuilder balance(BigDecimal balance) {
            this.balance = balance;
            return this;
        }

        public AccountBuilder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public AccountBuilder version(Long version) {
            this.version = version;
            return this;
        }

        /**
         * Set balance to zero
         */
        public AccountBuilder zeroBalance() {
            this.balance = BigDecimal.ZERO;
            return this;
        }

        /**
         * Set balance slightly less than required amount (for testing insufficient funds)
         * @param requiredAmount the amount needed for transaction
         */
        public AccountBuilder insufficientBalance(String requiredAmount) {
            BigDecimal required = new BigDecimal(requiredAmount);
            this.balance = required.subtract(new BigDecimal("0.01"));
            return this;
        }

        /**
         * Set currency to USD
         */
        public AccountBuilder usdCurrency() {
            this.currency = "USD";
            return this;
        }

        /**
         * Set currency to EUR
         */
        public AccountBuilder eurCurrency() {
            this.currency = "EUR";
            return this;
        }

        /**
         * Initialize with default valid values.
         * Generates random userId to avoid conflicts.
         * Provides reasonable defaults for testing.
         */
        public AccountBuilder withDefaultValues() {
            // Generate random userId to avoid conflicts
            this.userId = "user-" + UUID.randomUUID().toString().substring(0, 8);
            this.currency = "HKD";
            this.balance = new BigDecimal("1000.0000");
            return this;
        }

        public Account build() {
            Account account = Account.builder()
                    .userId(userId)
                    .balance(balance)
                    .currency(currency)
                    .build();

            // Only set ID if explicitly provided via id() method
            // Do NOT auto-generate ID here - let JPA handle it
            if (id != null) {
                try {
                    java.lang.reflect.Field idField = Account.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(account, id);
                } catch (Exception e) {
                    // Ignore if reflection fails
                }
            }

            if (version != null) {
                account.setVersion(version);
            }

            return account;
        }
    }

    // ========== CreateTransactionRequest Builder ==========

    public static class TransactionRequestBuilder {
        private String fromAccountId = UUID.randomUUID().toString();
        private String toAccountId = UUID.randomUUID().toString();
        private String amount = "100.00";
        private String currency = "HKD";
        private String idempotencyKey = "test-key-" + UUID.randomUUID();
        private String description = "Test transaction";

        public TransactionRequestBuilder fromAccountId(String fromAccountId) {
            this.fromAccountId = fromAccountId;
            return this;
        }

        public TransactionRequestBuilder fromAccountId(UUID fromAccountId) {
            this.fromAccountId = fromAccountId.toString();
            return this;
        }

        public TransactionRequestBuilder toAccountId(String toAccountId) {
            this.toAccountId = toAccountId;
            return this;
        }

        public TransactionRequestBuilder toAccountId(UUID toAccountId) {
            this.toAccountId = toAccountId.toString();
            return this;
        }

        public TransactionRequestBuilder amount(String amount) {
            this.amount = amount;
            return this;
        }

        public TransactionRequestBuilder currency(String currency) {
            this.currency = currency;
            return this;
        }

        public TransactionRequestBuilder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public TransactionRequestBuilder description(String description) {
            this.description = description;
            return this;
        }

        // ========== Convenience methods for common test scenarios ==========

        /**
         * Set amount to negative value (for testing validation)
         */
        public TransactionRequestBuilder negativeAmount() {
            this.amount = "-100.00";
            return this;
        }

        /**
         * Set amount to zero (for testing validation)
         */
        public TransactionRequestBuilder zeroAmount() {
            this.amount = "0.00";
            return this;
        }

        /**
         * Set amount to invalid format (for testing validation)
         */
        public TransactionRequestBuilder invalidAmount() {
            this.amount = "invalid-amount";
            return this;
        }

        /**
         * Set from account ID to invalid UUID format (for testing validation)
         */
        public TransactionRequestBuilder invalidFromAccountId() {
            this.fromAccountId = "invalid-uuid";
            return this;
        }

        /**
         * Set from account ID to empty string (for testing validation)
         */
        public TransactionRequestBuilder emptyFromAccountId() {
            this.fromAccountId = "";
            return this;
        }

        /**
         * Set to account ID to invalid UUID format (for testing validation)
         */
        public TransactionRequestBuilder invalidToAccountId() {
            this.toAccountId = "invalid-uuid";
            return this;
        }

        /**
         * Set to account ID to empty string (for testing validation)
         */
        public TransactionRequestBuilder emptyToAccountId() {
            this.toAccountId = "";
            return this;
        }

        /**
         * Initialize with default valid values.
         * Generates random UUIDs and idempotency key to avoid conflicts.
         */
        public TransactionRequestBuilder withDefaultValues() {
            this.fromAccountId = UUID.randomUUID().toString();
            this.toAccountId = UUID.randomUUID().toString();
            this.amount = "100.00";
            this.currency = "HKD";
            this.idempotencyKey = "key-" + UUID.randomUUID().toString().substring(0, 8);
            this.description = "Test transaction";
            return this;
        }

        public CreateTransactionRequest build() {
            return CreateTransactionRequest.newBuilder()
                    .setFromAccountId(fromAccountId)
                    .setToAccountId(toAccountId)
                    .setAmount(amount)
                    .setCurrency(currency)
                    .setIdempotencyKey(idempotencyKey)
                    .setDescription(description)
                    .build();
        }
    }

    // ========== Factory Methods ==========

    /**
     * Create a new Account builder
     */
    public static AccountBuilder anAccount() {
        return new AccountBuilder();
    }

    /**
     * Create a new CreateTransactionRequest builder
     */
    public static TransactionRequestBuilder aTransactionRequest() {
        return new TransactionRequestBuilder();
    }

    // ========== Predefined Test Scenarios ==========

    /**
     * Create an HKD account with specified user ID and balance.
     * For integration tests (no ID set - JPA will generate).
     */
    public static Account createHkdAccount(String userId, String balance) {
        return anAccount()
                .userId(userId)
                .balance(balance)
                .currency("HKD")
                .build();
    }

    /**
     * Create a USD account with specified user ID and balance.
     * For integration tests (no ID set - JPA will generate).
     */
    public static Account createUsdAccount(String userId, String balance) {
        return anAccount()
                .userId(userId)
                .balance(balance)
                .currency("USD")
                .build();
    }

    /**
     * Create an EUR account with specified user ID and balance.
     * For integration tests (no ID set - JPA will generate).
     */
    public static Account createEurAccount(String userId, String balance) {
        return anAccount()
                .userId(userId)
                .balance(balance)
                .currency("EUR")
                .build();
    }

    /**
     * Create an HKD account with auto-generated ID.
     * For unit tests where ID is required for mocking.
     */
    public static Account createHkdAccountWithId(String userId, String balance) {
        return anAccount()
                .id(UUID.randomUUID())
                .userId(userId)
                .balance(balance)
                .currency("HKD")
                .build();
    }

    /**
     * Create a USD account with auto-generated ID.
     * For unit tests where ID is required for mocking.
     */
    public static Account createUsdAccountWithId(String userId, String balance) {
        return anAccount()
                .id(UUID.randomUUID())
                .userId(userId)
                .balance(balance)
                .currency("USD")
                .build();
    }

    /**
     * Create an EUR account with auto-generated ID.
     * For unit tests where ID is required for mocking.
     */
    public static Account createEurAccountWithId(String userId, String balance) {
        return anAccount()
                .id(UUID.randomUUID())
                .userId(userId)
                .balance(balance)
                .currency("EUR")
                .build();
    }
}
