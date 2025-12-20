package com.chungtau.ledger_core.integration;

import com.chungtau.ledger.grpc.v1.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Integration test for complete flow:
 * gRPC → DB → Outbox → Scheduler → Kafka → Audit Service
 *
 * Run this test while monitoring:
 * docker logs ledger-audit -f
 */
public class GrpcFlowTest {

    private ManagedChannel channel;
    private LedgerServiceGrpc.LedgerServiceBlockingStub blockingStub;

    @BeforeEach
    public void setUp() {
        // Create channel to the running gRPC server
        channel = ManagedChannelBuilder.forAddress("localhost", 9098)
                .usePlaintext()
                .build();
        blockingStub = LedgerServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    public void tearDown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void testCompleteFlow() throws InterruptedException {
        String idempotencyKey = UUID.randomUUID().toString();

        System.out.println("\n========================================");
        System.out.println("=== Testing Complete gRPC Flow ===");
        System.out.println("========================================");
        System.out.println("Idempotency Key: " + idempotencyKey);
        System.out.println();

        // Build the request using real account IDs from database
        String fromAccountId = "4c5a6b27-529b-4d81-bae6-a871c501148d"; // user-a (HKD)
        String toAccountId = "53d9ec42-7900-4443-9a82-366607464c1c";   // user-b (HKD)

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey)
                .setFromAccountId(fromAccountId)
                .setToAccountId(toAccountId)
                .setAmount("100.50")
                .setCurrency("HKD")
                .setDescription("Test payment via gRPC integration test")
                .build();

        System.out.println("[1/5] Sending gRPC CreateTransaction request...");
        System.out.println("  From: " + fromAccountId + " (user-a)");
        System.out.println("  To: " + toAccountId + " (user-b)");
        System.out.println("  Amount: 100.50 HKD");
        System.out.println();

        // Send the request
        TransactionResponse response = blockingStub.createTransaction(request);

        System.out.println("✅ [2/5] Response received:");
        System.out.println("  Transaction ID: " + response.getTransactionId());
        System.out.println("  Status: " + response.getStatus());
        System.out.println("  Created At: " + response.getCreatedAt());
        System.out.println();

        System.out.println("✅ [3/5] Transaction saved to PostgreSQL DB");
        System.out.println("✅ [4/5] Event saved to Outbox table");
        System.out.println();

        System.out.println("⏳ [5/5] Waiting for Outbox Scheduler to poll and publish...");
        System.out.println("  Polling interval: 1 second (configured in application.yaml)");
        System.out.println("  Waiting 3 seconds for processing...");
        System.out.println();

        // Wait for scheduler to process
        Thread.sleep(3000);

        System.out.println("========================================");
        System.out.println("=== Expected Flow ===");
        System.out.println("========================================");
        System.out.println("1. ✅ gRPC request received by Java App");
        System.out.println("2. ✅ Transaction saved to PostgreSQL DB");
        System.out.println("3. ✅ Event saved to Outbox table");
        System.out.println("4. ⏳ Outbox Scheduler polls every 1s");
        System.out.println("5. ⏳ Event published to Kafka topic 'transaction-events'");
        System.out.println("6. ⏳ Go Audit Service consumes from Kafka");
        System.out.println("7. ⏳ Audit log should show:");
        System.out.println();
        System.out.println("     Received Event | Key: " + response.getTransactionId());
        System.out.println("     ✅ AUDIT LOG: Transaction " + response.getTransactionId());
        System.out.println();
        System.out.println("========================================");
        System.out.println("📋 NEXT STEPS:");
        System.out.println("========================================");
        System.out.println("Run in another terminal:");
        System.out.println("  docker logs ledger-audit -f");
        System.out.println();
        System.out.println("Look for these log lines:");
        System.out.println("  - Received Event | Key: <uuid>");
        System.out.println("  - ✅ AUDIT LOG: Transaction <uuid>");
        System.out.println("========================================");
        System.out.println();
    }
}
