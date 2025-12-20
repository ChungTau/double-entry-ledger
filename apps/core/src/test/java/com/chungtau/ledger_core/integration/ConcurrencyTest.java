package com.chungtau.ledger_core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.chungtau.ledger.grpc.v1.CreateTransactionRequest;
import com.chungtau.ledger.grpc.v1.TransactionResponse;
import com.chungtau.ledger_core.entity.Account;
import com.chungtau.ledger_core.repository.AccountRepository;
import com.chungtau.ledger_core.repository.TransactionRepository;
import com.chungtau.ledger_core.service.LedgerServiceImpl;

import io.grpc.stub.StreamObserver;

@SpringBootTest
class ConcurrencyTest {

    @Autowired
    private LedgerServiceImpl ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    private UUID accountIdA;
    private UUID accountIdB;

    @BeforeEach
    void setup() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();

        // Initialize two accounts with sufficient funds.
        // Both accounts need funds to ensure the bidirectional deadlock test 
        // (A->B and B->A) doesn't fail with "Insufficient Funds".
        Account accountA = Account.builder()
                .userId("user-a")
                .currency("HKD")
                .balance(new BigDecimal("1000.0000")) 
                .build();

        Account accountB = Account.builder()
                .userId("user-b")
                .currency("HKD")
                .balance(new BigDecimal("1000.0000"))
                .build();

        accountA = accountRepository.save(accountA);
        accountB = accountRepository.save(accountB);

        accountIdA = accountA.getId();
        accountIdB = accountB.getId();
    }

    /**
     * Test Focus: Race Condition & Data Consistency
     * Simulates 100 threads transferring $1 from Account A to Account B simultaneously.
     * * Scenario:
     * - Initial A: 1000
     * - Initial B: 1000
     * - Transfer: 100 transactions of $1 from A to B
     * * Expected Result:
     * - Final A: 1000 - 100 = 900
     * - Final B: 1000 + 100 = 1100
     */
    @Test
    void testConcurrentTransfer_Safety() throws InterruptedException {
        int threadCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        System.out.println("Starting concurrent test: 100 threads transferring funds...");

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.execute(() -> {
                try {
                    String idempotencyKey = "test-concurrent-" + index + "-" + UUID.randomUUID();
                    createTransaction(accountIdA, accountIdB, "1.00", idempotencyKey);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Transaction failed: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(); // Wait for all threads to complete
        executor.shutdown();

        Account updatedA = accountRepository.findById(accountIdA).orElseThrow();
        Account updatedB = accountRepository.findById(accountIdB).orElseThrow();

        System.out.println("Test completed!");
        System.out.println("Account A Balance: " + updatedA.getBalance());
        System.out.println("Account B Balance: " + updatedB.getBalance());

        // Assertions
        assertEquals(threadCount, successCount.get(), "All transactions should succeed");
        
        // Validation: Account A should decrease by 100
        assertEquals(0, new BigDecimal("900.0000").compareTo(updatedA.getBalance()), "Account A balance incorrect");
        
        // Validation: Account B should increase by 100 (Initial 1000 + 100)
        assertEquals(0, new BigDecimal("1100.0000").compareTo(updatedB.getBalance()), "Account B balance incorrect");
    }

    /**
     * Test Focus: Deadlock Prevention
     * Simulates mixed transactions (A -> B and B -> A) happening simultaneously.
     * * Without Lock Ordering (sorting locks by UUID), this test would hang indefinitely (Deadlock).
     * With Lock Ordering, it should complete successfully.
     * * Invariant Check:
     * The total money in the system (A + B) must remain constant.
     */
    @Test
    void testDeadlockPrevention() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        System.out.println("Starting deadlock test: Mixed A->B and B->A transactions...");

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.execute(() -> {
                try {
                    String idempotencyKey = "test-deadlock-" + index + "-" + UUID.randomUUID();
                    if (index % 2 == 0) {
                        // Even threads: Transfer from A to B
                        createTransaction(accountIdA, accountIdB, "1.00", idempotencyKey);
                    } else {
                        // Odd threads: Transfer from B to A
                        createTransaction(accountIdB, accountIdA, "1.00", idempotencyKey);
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        System.out.println("Deadlock test passed!");
        assertEquals(threadCount, successCount.get(), "All transactions should succeed");
        
        // Verify System Invariant (Conservation of Money)
        Account updatedA = accountRepository.findById(accountIdA).orElseThrow();
        Account updatedB = accountRepository.findById(accountIdB).orElseThrow();
        BigDecimal totalBalance = updatedA.getBalance().add(updatedB.getBalance());
        
        // Total = Initial A (1000) + Initial B (1000) = 2000
        assertEquals(0, new BigDecimal("2000.0000").compareTo(totalBalance), "Total system balance should remain 2000");
    }

    private void createTransaction(UUID from, UUID to, String amount, String key) {
        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setFromAccountId(from.toString())
                .setToAccountId(to.toString())
                .setAmount(amount)
                .setCurrency("HKD")
                .setIdempotencyKey(key)
                .setDescription("Integration Test")
                .build();

        ledgerService.createTransaction(request, new StreamObserver<TransactionResponse>() {
            @Override
            public void onNext(TransactionResponse value) {
                // Transaction successful
            }

            @Override
            public void onError(Throwable t) {
                throw new RuntimeException(t);
            }

            @Override
            public void onCompleted() {
                // Stream completed
            }
        });
    }
}