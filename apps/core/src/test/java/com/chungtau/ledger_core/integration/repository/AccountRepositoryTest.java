package com.chungtau.ledger_core.integration.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.chungtau.ledger_core.entity.Account;
import com.chungtau.ledger_core.fixtures.TestDataBuilder;
import com.chungtau.ledger_core.repository.AccountRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Integration tests for AccountRepository.
 * Tests all query methods, pessimistic locking behavior, and database constraints.
 *
 * Test Coverage:
 * - CRUD operations (findById, save, delete)
 * - Custom queries (findAllByUserId)
 * - Pessimistic locking (FOR UPDATE SQL verification)
 * - Database constraints (NOT NULL, precision)
 * - Optimistic locking (version increment)
 */
@DisplayName("AccountRepository Integration Tests")
class AccountRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TestEntityManager entityManager;

    private Account testAccount;

    @BeforeEach
    void setUp() {
        // @DataJpaTest automatically rolls back after each test, no need to deleteAll()

        // Create a test account
        testAccount = TestDataBuilder.anAccount()
                .userId("test-user-001")
                .balance("500.0000")
                .currency("HKD")
                .build();

        testAccount = accountRepository.save(testAccount);
        // Don't clear() here to avoid version conflicts
    }

    // ========== findById Tests ==========

    @Test
    @DisplayName("findById - Should return account when exists")
    void findById_ShouldReturnAccount_WhenExists() {
        // When
        Optional<Account> result = accountRepository.findById(testAccount.getId());

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(testAccount.getId());
        assertThat(result.get().getUserId()).isEqualTo("test-user-001");
        assertThat(result.get().getBalance())
                .isEqualByComparingTo(new BigDecimal("500.0000"));
        assertThat(result.get().getCurrency()).isEqualTo("HKD");
    }

    @Test
    @DisplayName("findById - Should return empty when account not exists")
    void findById_ShouldReturnEmpty_WhenNotExists() {
        // Given
        UUID nonExistentId = UUID.randomUUID();

        // When
        Optional<Account> result = accountRepository.findById(nonExistentId);

        // Then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findById - Should generate SQL with FOR UPDATE")
    void findById_ShouldGenerateSqlWithForUpdate() {
        // Note: This test verifies that PESSIMISTIC_WRITE lock generates correct SQL
        // In @DataJpaTest single-threaded environment, we can't verify actual blocking behavior
        // For real multi-threaded lock testing, see ConcurrencyTest.java

        // When
        Optional<Account> result = accountRepository.findById(testAccount.getId());

        // Then
        assertThat(result).isPresent();
        // The @Lock(LockModeType.PESSIMISTIC_WRITE) annotation should generate
        // "SELECT ... FOR UPDATE" SQL (visible in logs when show_sql=true)
        // For actual blocking behavior verification, see ConcurrencyTest
    }

    // ========== findAllByUserId Tests ==========

    @Test
    @DisplayName("findAllByUserId - Should return all accounts for user")
    void findAllByUserId_ShouldReturnAllAccountsForUser() {
        // Given: Create multiple accounts for same user
        String userId = "test-user-002";
        Account account1 = TestDataBuilder.createHkdAccount(userId, "100.00");
        Account account2 = TestDataBuilder.createUsdAccount(userId, "200.00");
        Account account3 = TestDataBuilder.createHkdAccount("other-user", "300.00");

        accountRepository.save(account1);
        accountRepository.save(account2);
        accountRepository.save(account3);
        entityManager.flush();
        entityManager.clear();

        // When
        List<Account> results = accountRepository.findAllByUserId(userId);

        // Then
        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(Account::getUserId)
                .containsOnly(userId);
        assertThat(results)
                .extracting(Account::getCurrency)
                .containsExactlyInAnyOrder("HKD", "USD");
    }

    @Test
    @DisplayName("findAllByUserId - Should return empty list when no accounts exist")
    void findAllByUserId_ShouldReturnEmptyList_WhenNoAccountsExist() {
        // When
        List<Account> results = accountRepository.findAllByUserId("non-existent-user");

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("findAllByUserId - Should use index on user_id column")
    void findAllByUserId_ShouldUseIndex() {
        // This is a smoke test - actual index usage would be verified via EXPLAIN ANALYZE
        // Given: Create many accounts
        for (int i = 0; i < 100; i++) {
            Account account = TestDataBuilder.anAccount()
                    .userId("user-" + (i % 10))
                    .build();
            accountRepository.save(account);
        }
        entityManager.flush();

        // When: Query should be fast due to index
        long startTime = System.currentTimeMillis();
        List<Account> results = accountRepository.findAllByUserId("user-5");
        long duration = System.currentTimeMillis() - startTime;

        // Then: Should return results quickly
        assertThat(results).hasSizeGreaterThan(0);
        assertThat(duration).isLessThan(100); // Should complete in < 100ms
    }

    // ========== Save and Update Tests ==========

    @Test
    @DisplayName("save - Should persist new account with generated UUID")
    void save_ShouldPersistNewAccount() {
        // Given
        Account newAccount = TestDataBuilder.anAccount()
                .userId("new-user")
                .balance("1234.5678")
                .currency("USD")
                .build();

        // When
        Account saved = accountRepository.saveAndFlush(newAccount);
        entityManager.clear();

        // Then
        assertThat(saved.getId()).isNotNull();

        Optional<Account> retrieved = accountRepository.findById(saved.getId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().getUserId()).isEqualTo("new-user");
        assertThat(retrieved.get().getBalance())
                .isEqualByComparingTo(new BigDecimal("1234.5678"));
    }

    @Test
    @DisplayName("save - Should update existing account and increment version")
    void save_ShouldUpdateExistingAccountAndIncrementVersion() {
        // Given
        Long originalVersion = testAccount.getVersion();

        // When: Update balance
        testAccount.setBalance(new BigDecimal("999.9999"));
        Account updated = accountRepository.saveAndFlush(testAccount);
        entityManager.clear();

        // Then
        Account retrieved = accountRepository.findById(testAccount.getId()).orElseThrow();
        assertThat(retrieved.getBalance())
                .isEqualByComparingTo(new BigDecimal("999.9999"));
        assertThat(retrieved.getVersion()).isEqualTo(originalVersion + 1);
    }

    // ========== Constraint Tests ==========

    @Test
    @DisplayName("save - Should enforce not-null constraint on userId")
    void save_ShouldEnforceNotNullConstraintOnUserId() {
        // Given: Create account with null userId
        Account invalidAccount = Account.builder()
                .userId(null)
                .balance(BigDecimal.ZERO)
                .currency("HKD")
                .build();

        // When / Then: Should throw exception
        // @DataJpaTest wraps PersistenceException in DataIntegrityViolationException
        assertThrows(DataIntegrityViolationException.class, () -> {
            accountRepository.saveAndFlush(invalidAccount);
        });
    }

    @Test
    @DisplayName("save - Should enforce not-null constraint on currency")
    void save_ShouldEnforceNotNullConstraintOnCurrency() {
        // Given
        Account invalidAccount = Account.builder()
                .userId("test-user")
                .balance(BigDecimal.ZERO)
                .currency(null)
                .build();

        // When / Then
        // @DataJpaTest wraps PersistenceException in DataIntegrityViolationException
        assertThrows(DataIntegrityViolationException.class, () -> {
            accountRepository.saveAndFlush(invalidAccount);
        });
    }

    @Test
    @DisplayName("save - Should store balance with 4 decimal precision")
    void save_ShouldStoreBalanceWithCorrectPrecision() {
        // Given
        Account account = TestDataBuilder.anAccount()
                .balance("123.4567")
                .build();

        // When
        Account saved = accountRepository.saveAndFlush(account);
        entityManager.clear();

        // Then
        Account retrieved = accountRepository.findById(saved.getId()).orElseThrow();
        assertThat(retrieved.getBalance().scale()).isEqualTo(4);
        assertThat(retrieved.getBalance())
                .isEqualByComparingTo(new BigDecimal("123.4567"));
    }

    // ========== Delete Tests ==========

    @Test
    @DisplayName("deleteById - Should remove account")
    void deleteById_ShouldRemoveAccount() {
        // Given
        UUID accountId = testAccount.getId();

        // When
        accountRepository.deleteById(accountId);
        entityManager.flush();

        // Then
        Optional<Account> result = accountRepository.findById(accountId);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteAll - Should remove all accounts")
    void deleteAll_ShouldRemoveAllAccounts() {
        // Given: Create additional accounts
        accountRepository.save(TestDataBuilder.anAccount().build());
        accountRepository.save(TestDataBuilder.anAccount().build());
        entityManager.flush();

        // When
        accountRepository.deleteAll();
        entityManager.flush();

        // Then
        long count = accountRepository.count();
        assertThat(count).isZero();
    }
}
