package com.chungtau.ledger_core.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import com.chungtau.ledger.grpc.v1.AccountResponse;
import com.chungtau.ledger.grpc.v1.BalanceResponse;
import com.chungtau.ledger.grpc.v1.CreateAccountRequest;
import com.chungtau.ledger.grpc.v1.CreateTransactionRequest;
import com.chungtau.ledger.grpc.v1.GetBalanceRequest;
import com.chungtau.ledger.grpc.v1.LedgerServiceGrpc;
import com.chungtau.ledger.grpc.v1.ListAccountsRequest;
import com.chungtau.ledger.grpc.v1.ListAccountsResponse;
import com.chungtau.ledger.grpc.v1.TransactionResponse;
import com.chungtau.ledger_core.config.DataInitializer;
import com.chungtau.ledger_core.entity.Account;
import com.chungtau.ledger_core.entity.Transaction;
import com.chungtau.ledger.grpc.v1.TransactionCreatedEvent;
import com.chungtau.ledger_core.exception.AccountNotFoundException;
import com.chungtau.ledger_core.repository.AccountRepository;
import com.chungtau.ledger_core.repository.TransactionRepository;
import com.chungtau.ledger_core.util.LogMaskingUtil;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

@Slf4j
@GrpcService
@RequiredArgsConstructor
public class LedgerServiceImpl extends LedgerServiceGrpc.LedgerServiceImplBase {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final OutboxEventService outboxEventService;

    private static final String TOPIC_TRANSACTION_CREATED = "transaction-events";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    /**
     * Handles money transfer requests between accounts with full ACID compliance.
     * 
     * This method performs the following steps atomically:
     * 1. Idempotency Check (prevents double-spending).
     * 2. Balance & Currency Validation.
     * 3. Double-Entry execution (Debit source, Credit destination).
     * 4. Persistence of the Transaction and its Entries.
     */
    @Override
    @Transactional
    public void createTransaction(CreateTransactionRequest request, StreamObserver<TransactionResponse> responseObserver) {
        try {
            // 1. Idempotency Check
            if (transactionRepository.existsByIdempotencyKey(request.getIdempotencyKey())) {
                log.warn("Duplicate transaction request detected for key: {}", LogMaskingUtil.mask(request.getIdempotencyKey()));
                responseObserver.onError(Status.ALREADY_EXISTS
                        .withDescription("Transaction with this Idempotency Key already exists.")
                        .asRuntimeException());
                return;
            }

            // 2. Input Validation
            if (request.getFromAccountId().isEmpty() || request.getToAccountId().isEmpty()) {
                throw new IllegalArgumentException("Source and Destination Account IDs are required");
            }

            UUID fromAccountId;
            UUID toAccountId;
            BigDecimal amount;

            try {
                fromAccountId = UUID.fromString(request.getFromAccountId());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid source account ID format");
            }

            try {
                toAccountId = UUID.fromString(request.getToAccountId());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid destination account ID format");
            }

            try {
                amount = new BigDecimal(request.getAmount());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid amount format");
            }

            // 3. Lock Ordering (prevents Deadlock)
            UUID firstLock = fromAccountId.compareTo(toAccountId) < 0 ? fromAccountId : toAccountId;
            UUID secondLock = fromAccountId.compareTo(toAccountId) < 0 ? toAccountId : fromAccountId;

            // 4. Fetch accounts with Pessimistic Lock (prevents Race Condition)
            Account account1 = accountRepository.findById(firstLock)
                    .orElseThrow(() -> {
                        log.warn("Account not found: {}", LogMaskingUtil.maskUuid(firstLock.toString()));
                        return new AccountNotFoundException("Account not found");
                    });

            Account account2 = accountRepository.findById(secondLock)
                    .orElseThrow(() -> {
                        log.warn("Account not found: {}", LogMaskingUtil.maskUuid(secondLock.toString()));
                        return new AccountNotFoundException("Account not found");
                    });

            Account fromAccount = fromAccountId.equals(firstLock) ? account1 : account2;
            Account toAccount = toAccountId.equals(firstLock) ? account1 : account2;

            // 5. Business Validation
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Transaction amount must be positive");
            }

            if (!fromAccount.getCurrency().equals(request.getCurrency()) || 
                !toAccount.getCurrency().equals(request.getCurrency())) {
                throw new IllegalArgumentException("Currency mismatch between accounts and request");
            }

            if (fromAccount.getBalance().compareTo(amount) < 0) {
                log.warn("Insufficient funds for account: {}", LogMaskingUtil.maskUuid(fromAccount.getId().toString()));
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("Insufficient funds in source account")
                        .asRuntimeException());
                return;
            }

            // 6. Update Balance
            fromAccount.setBalance(fromAccount.getBalance().subtract(amount));
            toAccount.setBalance(toAccount.getBalance().add(amount));
            accountRepository.save(fromAccount);
            accountRepository.save(toAccount);

            Transaction transaction = Transaction.createTransfer(
                request.getIdempotencyKey(),
                request.getDescription(),
                fromAccount,
                toAccount,
                amount
            );
            transactionRepository.saveAndFlush(transaction);

            log.info("Transaction processed successfully. ID: {}", LogMaskingUtil.maskUuid(transaction.getId().toString()));

            String createdAt = transaction.getBookedAt() != null ? 
                               transaction.getBookedAt().toString() : 
                               Instant.now().toString();

            TransactionCreatedEvent event = TransactionCreatedEvent.newBuilder()
                    .setTransactionId(transaction.getId().toString())
                    .setIdempotencyKey(transaction.getIdempotencyKey())
                    .setFromAccountId(fromAccount.getId().toString())
                    .setToAccountId(toAccount.getId().toString())
                    .setAmount(amount.toString())
                    .setCurrency(request.getCurrency())
                    .setStatus(transaction.getStatus().toString())
                    .setBookedAt(createdAt)
                    .build();

            // Save to outbox within the same transaction
            // aggregateId is used as Kafka message key to ensure event ordering per transaction
            outboxEventService.createOutboxEvent(
                    transaction.getId().toString(), // aggregateId (used as Kafka key)
                    "TRANSACTION", // aggregateType
                    "TRANSACTION_CREATED", // eventType
                    event, // payload
                    TOPIC_TRANSACTION_CREATED // topic
            );

            log.debug("Transaction event saved to outbox: {}", transaction.getId());

            // 7. Construct Response

            TransactionResponse response = TransactionResponse.newBuilder()
                    .setTransactionId(transaction.getId().toString())
                    .setStatus(transaction.getStatus().toString())
                    .setCreatedAt(createdAt) 
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            log.error("Validation failed for transaction: {}", e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (AccountNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND
                    .withDescription(e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            log.error("Internal system error during transaction processing", e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Internal processing error")
                    .asRuntimeException());
        }
    }

    /**
     * Retrieves the current balance and version for a specific account.
     * Uses JPA repository to fetch real-time data from PostgreSQL.
     */
    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<BalanceResponse> responseObserver) {
        try {
            if (request.getAccountId().isEmpty()) {
                throw new IllegalArgumentException("Account ID is required");
            }

            UUID accountId;
            try {
                accountId = UUID.fromString(request.getAccountId());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid account ID format");
            }
            
            Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account not found"));

            BalanceResponse response = BalanceResponse.newBuilder()
                    .setAccountId(account.getId().toString())
                    .setCurrency(account.getCurrency())
                    .setBalance(account.getBalance().toString())
                    .setVersion(account.getVersion())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (IllegalArgumentException e) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription(e.getMessage())
                .asRuntimeException());
        } catch (AccountNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND
                .withDescription(e.getMessage())
                .asRuntimeException());
        } catch (Exception e) {
            log.error("Error fetching balance", e);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Failed to fetch balance")
                .asRuntimeException());
        }
    }

    /**
     * Creates a new account for a user with optional initial balance.
     * If initial_balance > 0, a Genesis transaction is created from the system
     * Equity account to maintain double-entry bookkeeping compliance.
     */
    @Override
    @Transactional
    public void createAccount(CreateAccountRequest request, StreamObserver<AccountResponse> responseObserver) {
        try {
            // 1. Validate user_id
            if (request.getUserId() == null || request.getUserId().isBlank()) {
                throw new IllegalArgumentException("User ID is required");
            }

            // 2. Validate currency (exactly 3 characters)
            if (request.getCurrency() == null || request.getCurrency().length() != 3) {
                throw new IllegalArgumentException("Currency must be exactly 3 characters");
            }

            // 3. Parse initial_balance (default to 0 if empty)
            BigDecimal initialBalance = BigDecimal.ZERO;
            if (request.getInitialBalance() != null && !request.getInitialBalance().isBlank()) {
                try {
                    initialBalance = new BigDecimal(request.getInitialBalance());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid initial balance format");
                }
            }

            // 4. Validate initial_balance >= 0
            if (initialBalance.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Initial balance cannot be negative");
            }

            // 5. Create account with balance = 0 initially
            Account newAccount = Account.builder()
                .userId(request.getUserId())
                .currency(request.getCurrency().toUpperCase())
                .balance(BigDecimal.ZERO)
                .build();

            // 6. Save account
            Account savedAccount = accountRepository.save(newAccount);
            log.info("Created new account: {} for user: {}",
                LogMaskingUtil.maskUuid(savedAccount.getId().toString()),
                LogMaskingUtil.mask(request.getUserId()));

            // 7. If initial_balance > 0, create Genesis transaction
            if (initialBalance.compareTo(BigDecimal.ZERO) > 0) {
                createGenesisTransaction(savedAccount, initialBalance);
            }

            // 8. Build and return response
            AccountResponse response = AccountResponse.newBuilder()
                .setAccountId(savedAccount.getId().toString())
                .setUserId(savedAccount.getUserId())
                .setCurrency(savedAccount.getCurrency())
                .setBalance(savedAccount.getBalance().toString())
                .setVersion(savedAccount.getVersion() != null ? savedAccount.getVersion() : 0L)
                .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            log.error("Validation failed for createAccount: {}", e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription(e.getMessage())
                .asRuntimeException());
        } catch (Exception e) {
            log.error("Internal error during account creation", e);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Failed to create account")
                .asRuntimeException());
        }
    }

    /**
     * Creates a Genesis transaction to fund the new account from system Equity account.
     * This maintains double-entry bookkeeping compliance for opening balances.
     */
    private void createGenesisTransaction(Account targetAccount, BigDecimal amount) {
        String currency = targetAccount.getCurrency();

        // Find the system equity account for this currency
        Account equityAccount = accountRepository
            .findByUserIdAndCurrency(DataInitializer.SYSTEM_USER_ID, currency)
            .orElseThrow(() -> {
                log.error("System Equity account not found for currency: {}", currency);
                return new IllegalStateException("System Equity account not found for currency: " + currency);
            });

        // Create the Genesis transaction
        String idempotencyKey = "genesis-" + targetAccount.getId().toString();

        Transaction genesisTransaction = Transaction.createTransfer(
            idempotencyKey,
            "Opening Balance",
            equityAccount,
            targetAccount,
            amount
        );

        // Update balances
        equityAccount.setBalance(equityAccount.getBalance().subtract(amount));
        targetAccount.setBalance(targetAccount.getBalance().add(amount));

        accountRepository.save(equityAccount);
        accountRepository.save(targetAccount);
        transactionRepository.saveAndFlush(genesisTransaction);

        log.info("Created Genesis transaction {} for account {} with amount {}",
            LogMaskingUtil.maskUuid(genesisTransaction.getId().toString()),
            LogMaskingUtil.maskUuid(targetAccount.getId().toString()),
            amount);
    }

    /**
     * Lists all accounts for a specific user with pagination support.
     */
    @Override
    public void listAccounts(ListAccountsRequest request, StreamObserver<ListAccountsResponse> responseObserver) {
        try {
            // 1. Validate user_id
            if (request.getUserId() == null || request.getUserId().isBlank()) {
                throw new IllegalArgumentException("User ID is required");
            }

            // 2. Parse pagination parameters
            int page = Math.max(0, request.getPage());
            int pageSize = request.getPageSize();
            if (pageSize <= 0) {
                pageSize = DEFAULT_PAGE_SIZE;
            } else if (pageSize > MAX_PAGE_SIZE) {
                pageSize = MAX_PAGE_SIZE;
            }

            Pageable pageable = PageRequest.of(page, pageSize);

            // 3. Query accounts
            Page<Account> accountPage = accountRepository.findAllByUserId(request.getUserId(), pageable);

            // 4. Build response
            ListAccountsResponse.Builder responseBuilder = ListAccountsResponse.newBuilder()
                .setTotalCount(accountPage.getTotalElements())
                .setPage(page)
                .setPageSize(pageSize);

            for (Account account : accountPage.getContent()) {
                AccountResponse accountResponse = AccountResponse.newBuilder()
                    .setAccountId(account.getId().toString())
                    .setUserId(account.getUserId())
                    .setCurrency(account.getCurrency())
                    .setBalance(account.getBalance().toString())
                    .setVersion(account.getVersion() != null ? account.getVersion() : 0L)
                    .build();
                responseBuilder.addAccounts(accountResponse);
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (IllegalArgumentException e) {
            log.error("Validation failed for listAccounts: {}", e.getMessage());
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription(e.getMessage())
                .asRuntimeException());
        } catch (Exception e) {
            log.error("Internal error during listing accounts", e);
            responseObserver.onError(Status.INTERNAL
                .withDescription("Failed to list accounts")
                .asRuntimeException());
        }
    }
}