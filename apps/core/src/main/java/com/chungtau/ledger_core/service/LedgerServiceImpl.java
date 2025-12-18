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
        log.info("Processing transaction: from {} to {} with amount {}", 
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
    }

    /**
     * Retrieves the current balance and version for a specific account.
     */
    @Override
    public void getBalance(GetBalanceRequest request, StreamObserver<BalanceResponse> responseObserver) {
        log.info("Fetching balance for account: {}", request.getAccountId());

        // Mock balance response
        BalanceResponse response = BalanceResponse.newBuilder()
                .setAccountId(request.getAccountId())
                .setCurrency("HKD")
                .setBalance("1000.00")
                .setVersion(1L) // Version is used for Optimistic Locking
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}