package com.chungtau.ledger_core.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import com.chungtau.ledger.grpc.v1.BalanceResponse;
import com.chungtau.ledger.grpc.v1.CreateTransactionRequest;
import com.chungtau.ledger.grpc.v1.GetBalanceRequest;
import com.chungtau.ledger.grpc.v1.LedgerServiceGrpc;
import com.chungtau.ledger.grpc.v1.TransactionResponse;
import com.chungtau.ledger_core.entity.Account;
import com.chungtau.ledger_core.entity.Transaction;
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

            // 7. Construct Response
            String createdAt = transaction.getBookedAt() != null ? 
                               transaction.getBookedAt().toString() : 
                               Instant.now().toString();

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
}