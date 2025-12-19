package com.chungtau.ledger_core.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chungtau.ledger_core.entity.Account;
import com.chungtau.ledger_core.entity.Transaction;
import com.chungtau.ledger_core.entity.TransactionStatus;
import com.chungtau.ledger_core.fixtures.TestDataBuilder;
import com.chungtau.ledger_core.repository.AccountRepository;
import com.chungtau.ledger_core.repository.TransactionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * Integration tests for TransactionRepository.
 * Tests all query methods, idempotency constraints, and time-based queries.
 *
 * Test Coverage:
 * - Idempotency key operations (existsByIdempotencyKey, findByIdempotencyKey)
 * - Unique constraint validation
 * - Reference ID queries
 * - Time range queries (findByBookedAtBetween)
 * - Cascade persistence (TransactionEntry)
 * - Immutability (@Immutable annotation)
 */
@DisplayName("TransactionRepository Integration Tests")
class TransactionRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Account fromAccount;
    private Account toAccount;

    @BeforeEach
    void setUp() {
        // @DataJpaTest automatically rolls back after each test, no need to deleteAll()

        // Create test accounts
        fromAccount = TestDataBuilder.createHkdAccount("user-from", "1000.00");
        toAccount = TestDataBuilder.createHkdAccount("user-to", "500.00");

        fromAccount = accountRepository.saveAndFlush(fromAccount);
        toAccount = accountRepository.saveAndFlush(toAccount);
        entityManager.clear();
    }

    // ========== existsByIdempotencyKey Tests ==========

    @Test
    @DisplayName("existsByIdempotencyKey - Should return true when key exists")
    void existsByIdempotencyKey_ShouldReturnTrue_WhenKeyExists() {
        // Given
        String idempotencyKey = "test-key-001";
        Transaction tx = Transaction.createTransfer(
                idempotencyKey,
                "Test transaction",
                fromAccount,
                toAccount,
                new BigDecimal("50.00")
        );
        transactionRepository.saveAndFlush(tx);

        // When
        boolean exists = transactionRepository.existsByIdempotencyKey(idempotencyKey);

        // Then
        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("existsByIdempotencyKey - Should return false when key does not exist")
    void existsByIdempotencyKey_ShouldReturnFalse_WhenKeyDoesNotExist() {
        // When
        boolean exists = transactionRepository.existsByIdempotencyKey("non-existent-key");

        // Then
        assertThat(exists).isFalse();
    }

    // ========== findByIdempotencyKey Tests ==========

    @Test
    @DisplayName("findByIdempotencyKey - Should return transaction when key exists")
    void findByIdempotencyKey_ShouldReturnTransaction_WhenKeyExists() {
        // Given
        String idempotencyKey = "test-key-002";
        Transaction tx = Transaction.createTransfer(
                idempotencyKey,
                "Test description",
                fromAccount,
                toAccount,
                new BigDecimal("75.00")
        );
        Transaction saved = transactionRepository.saveAndFlush(tx);

        // When
        Optional<Transaction> result = transactionRepository.findByIdempotencyKey(idempotencyKey);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(saved.getId());
        assertThat(result.get().getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(result.get().getReferenceId()).isEqualTo("Test description");
    }

    @Test
    @DisplayName("findByIdempotencyKey - Should return empty when key does not exist")
    void findByIdempotencyKey_ShouldReturnEmpty_WhenKeyDoesNotExist() {
        // When
        Optional<Transaction> result = transactionRepository.findByIdempotencyKey("missing-key");

        // Then
        assertThat(result).isEmpty();
    }

    // ========== Unique Constraint Tests ==========

    @Test
    @DisplayName("save - Should enforce unique constraint on idempotency key")
    void save_ShouldEnforceUniqueConstraintOnIdempotencyKey() {
        // Given: Save first transaction
        String duplicateKey = "duplicate-key";
        Transaction tx1 = Transaction.createTransfer(
                duplicateKey,
                "First transaction",
                fromAccount,
                toAccount,
                new BigDecimal("10.00")
        );
        transactionRepository.saveAndFlush(tx1);

        // When: Try to save second transaction with same key
        Transaction tx2 = Transaction.createTransfer(
                duplicateKey,
                "Second transaction",
                fromAccount,
                toAccount,
                new BigDecimal("20.00")
        );

        // Then: Should throw constraint violation
        assertThrows(DataIntegrityViolationException.class, () -> {
            transactionRepository.saveAndFlush(tx2);
        });
    }

    // ========== findByReferenceId Tests ==========

    @Test
    @DisplayName("findByReferenceId - Should return all transactions with same reference ID")
    void findByReferenceId_ShouldReturnAllTransactionsWithSameReferenceId() {
        // Given: Create multiple transactions with same reference ID
        String referenceId = "order-12345";
        Transaction tx1 = Transaction.createTransfer(
                "key-1",
                referenceId,
                fromAccount,
                toAccount,
                new BigDecimal("100.00")
        );
        Transaction tx2 = Transaction.createTransfer(
                "key-2",
                referenceId,
                fromAccount,
                toAccount,
                new BigDecimal("200.00")
        );
        Transaction tx3 = Transaction.createTransfer(
                "key-3",
                "different-reference",
                fromAccount,
                toAccount,
                new BigDecimal("300.00")
        );

        transactionRepository.save(tx1);
        transactionRepository.save(tx2);
        transactionRepository.save(tx3);
        transactionRepository.flush();

        // When
        List<Transaction> results = transactionRepository.findByReferenceId(referenceId);

        // Then
        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(Transaction::getReferenceId)
                .containsOnly(referenceId);
    }

    @Test
    @DisplayName("findByReferenceId - Should return empty list when reference ID not found")
    void findByReferenceId_ShouldReturnEmptyList_WhenReferenceIdNotFound() {
        // When
        List<Transaction> results = transactionRepository.findByReferenceId("non-existent-ref");

        // Then
        assertThat(results).isEmpty();
    }

    // ========== findByBookedAtBetween Tests ==========

    @Test
    @DisplayName("findByBookedAtBetween - Should return transactions within time range")
    void findByBookedAtBetween_ShouldReturnTransactionsWithinTimeRange() {
        // Given: Create transactions
        Instant now = Instant.now();
        Instant yesterday = now.minus(1, ChronoUnit.DAYS);

        // Recent transactions
        Transaction tx1 = Transaction.createTransfer(
                "key-recent-1",
                "Recent transaction 1",
                fromAccount,
                toAccount,
                new BigDecimal("20.00")
        );
        Transaction tx2 = Transaction.createTransfer(
                "key-recent-2",
                "Recent transaction 2",
                fromAccount,
                toAccount,
                new BigDecimal("30.00")
        );
        transactionRepository.save(tx1);
        transactionRepository.save(tx2);
        transactionRepository.flush();

        // When: Query for last 24 hours
        List<Transaction> results = transactionRepository.findByBookedAtBetween(
                yesterday,
                now.plus(1, ChronoUnit.HOURS)
        );

        // Then: Should return recent transactions
        assertThat(results).hasSizeGreaterThanOrEqualTo(2);
        assertThat(results)
                .extracting(Transaction::getIdempotencyKey)
                .contains("key-recent-1", "key-recent-2");
    }

    @Test
    @DisplayName("findByBookedAtBetween - Should return empty list when no transactions in range")
    void findByBookedAtBetween_ShouldReturnEmptyList_WhenNoTransactionsInRange() {
        // Given: Save a transaction
        Transaction tx = Transaction.createTransfer(
                "key-today",
                "Today transaction",
                fromAccount,
                toAccount,
                new BigDecimal("50.00")
        );
        transactionRepository.saveAndFlush(tx);

        // When: Query for future date range
        Instant futureStart = Instant.now().plus(10, ChronoUnit.DAYS);
        Instant futureEnd = Instant.now().plus(20, ChronoUnit.DAYS);
        List<Transaction> results = transactionRepository.findByBookedAtBetween(futureStart, futureEnd);

        // Then
        assertThat(results).isEmpty();
    }

    // ========== Cascade Persist Tests ==========

    @Test
    @DisplayName("save - Should cascade persist transaction entries")
    void save_ShouldCascadePersistTransactionEntries() {
        // Given
        Transaction tx = Transaction.createTransfer(
                "cascade-test",
                "Test cascade",
                fromAccount,
                toAccount,
                new BigDecimal("100.00")
        );

        // When
        Transaction saved = transactionRepository.saveAndFlush(tx);
        entityManager.clear();

        // Then: Entries should be persisted
        Transaction retrieved = transactionRepository.findById(saved.getId()).orElseThrow();
        assertThat(retrieved.getEntries()).hasSize(2);
        assertThat(retrieved.getEntries())
                .allMatch(entry -> entry.getId() != null);
    }

    // ========== Transaction Status Tests ==========

    @Test
    @DisplayName("save - Should persist transaction with POSTED status")
    void save_ShouldPersistTransactionWithPostedStatus() {
        // Given
        Transaction tx = Transaction.createTransfer(
                "status-test",
                "Status test",
                fromAccount,
                toAccount,
                new BigDecimal("25.00")
        );

        // When
        Transaction saved = transactionRepository.saveAndFlush(tx);

        // Then
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.POSTED);
    }

    // ========== Immutability Tests ==========

    @Test
    @DisplayName("save - Should ignore updates due to @Immutable")
    void save_ShouldIgnoreUpdatesDueToImmutable() {
        // Given: Save initial transaction
        Transaction tx = Transaction.createTransfer(
                "immutable-test",
                "Original description",
                fromAccount,
                toAccount,
                new BigDecimal("100.00")
        );
        Transaction saved = transactionRepository.saveAndFlush(tx);
        String originalReferenceId = saved.getReferenceId();
        String originalIdempotencyKey = saved.getIdempotencyKey();

        // Force flush and clear L1 cache to ensure real DB state
        entityManager.flush();
        entityManager.clear();

        // When: Try to create a new transaction with same ID but different values
        // Note: Since Transaction is @Immutable and has no setters, we can't directly modify it.
        // This test verifies that Hibernate treats the entity as immutable.
        // If we try to persist a "modified" version (e.g., via reflection or a new instance),
        // Hibernate should ignore the update.

        // Re-read the transaction
        Transaction reloaded = transactionRepository.findById(saved.getId()).orElseThrow();

        // Attempt to use reflection to modify the referenceId (bypassing immutability)
        try {
            java.lang.reflect.Field referenceIdField = Transaction.class.getDeclaredField("referenceId");
            referenceIdField.setAccessible(true);
            referenceIdField.set(reloaded, "Modified description");
        } catch (Exception e) {
            // If reflection fails, that's actually good (field is truly immutable)
        }

        // Try to save the "modified" entity
        transactionRepository.save(reloaded);
        entityManager.flush();
        entityManager.clear();

        // Then: Re-read from DB and verify values are unchanged
        Transaction finalReload = transactionRepository.findById(saved.getId()).orElseThrow();
        assertThat(finalReload.getReferenceId()).isEqualTo(originalReferenceId);
        assertThat(finalReload.getIdempotencyKey()).isEqualTo(originalIdempotencyKey);

        // Verify that @Immutable prevents updates (values should remain original)
        // Even if we tried to modify via reflection, Hibernate's @Immutable should prevent DB update
    }
}
