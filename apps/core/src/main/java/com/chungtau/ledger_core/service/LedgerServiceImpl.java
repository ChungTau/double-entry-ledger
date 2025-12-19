package com.chungtau.ledger_core.service;

import java.time.Instant;
import java.util.UUID;

import com.chungtau.ledger.grpc.v1.BalanceResponse;
import com.chungtau.ledger.grpc.v1.CreateTransactionRequest;
import com.chungtau.ledger.grpc.v1.GetBalanceRequest;
import com.chungtau.ledger.grpc.v1.LedgerServiceGrpc;
import com.chungtau.ledger.grpc.v1.TransactionResponse;

import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;

@Slf4j
@GrpcService 
public class LedgerServiceImpl extends LedgerServiceGrpc.LedgerServiceImplBase {

    /**
     * Handles money transfer requests between accounts.
     * Currently implemented with mock data for initial setup verification.
     */
    @Override
    public void createTransaction(CreateTransactionRequest request, StreamObserver<TransactionResponse> responseObserver) {
        try {
            // Validate required fields
            if (request.getFromAccountId() == null || request.getFromAccountId().isEmpty()) {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("fromAccountId is required")
                    .asRuntimeException());
                return;
            }
            if (request.getToAccountId() == null || request.getToAccountId().isEmpty()) {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("toAccountId is required")
                    .asRuntimeException());
                return;
            }
            if (request.getAmount() == null || request.getAmount().isEmpty()) {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("amount is required")
                    .asRuntimeException());
                return;
            }

            log.info("Processing transaction: from [MASKED] to [MASKED] with amount [MASKED]");
            log.debug("Transaction details - fromAccountId: {}, toAccountId: {}, amount: {}",
                    request.getFromAccountId(), request.getToAccountId(), request.getAmount());

            // TODO: Implement idempotency check using Redis
            // TODO: Implement double-entry business logic and ACID persistence via JPA
            // TODO: Publish event to Kafka upon successful commit

            // Mock successful response
            TransactionResponse response = TransactionResponse.newBuilder()
                    .setTransactionId(UUID.randomUUID().toString())
                    .setStatus("POSTED")
                    .setCreatedAt(Instant.now().toString())
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error processing transaction", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to process transaction: " + e.getMessage())
                .asRuntimeException());
        }
    }

    /**
     * Retrieves the current balance and version for a specific account.
     */
    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<BalanceResponse> responseObserver) {
        try {
            // Validate required fields
            if (request.getAccountId() == null || request.getAccountId().isEmpty()) {
                responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                    .withDescription("accountId is required")
                    .asRuntimeException());
                return;
            }

            log.info("Fetching balance for account: [MASKED]");
            log.debug("Balance request for accountId: {}", request.getAccountId());

            // Mock balance response
            BalanceResponse response = BalanceResponse.newBuilder()
                    .setAccountId(request.getAccountId())
                    .setCurrency("HKD")
                    .setBalance("1000.00")
                    .setVersion(1L) // Version is used for Optimistic Locking
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error fetching balance", e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                .withDescription("Failed to fetch balance: " + e.getMessage())
                .asRuntimeException());
        }
    }
}