package com.chungtau.ledger_core.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.chungtau.ledger.grpc.v1.AccountResponse;
import com.chungtau.ledger.grpc.v1.CreateAccountRequest;
import com.chungtau.ledger.grpc.v1.CreateTransactionRequest;
import com.chungtau.ledger.grpc.v1.ListAccountsRequest;
import com.chungtau.ledger.grpc.v1.ListAccountsResponse;
import com.chungtau.ledger.grpc.v1.TransactionResponse;
import com.chungtau.ledger.grpc.v1.TransactionCreatedEvent;
import com.chungtau.ledger_core.config.DataInitializer;
import com.chungtau.ledger_core.entity.Account;
import com.chungtau.ledger_core.entity.Transaction;
import com.chungtau.ledger_core.fixtures.TestDataBuilder;
import com.chungtau.ledger_core.repository.AccountRepository;
import com.chungtau.ledger_core.repository.TransactionRepository;
import com.chungtau.ledger_core.service.LedgerServiceImpl;
import com.chungtau.ledger_core.service.OutboxEventService;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

@ExtendWith(MockitoExtension.class)
class LedgerServiceImplTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private StreamObserver<TransactionResponse> responseObserver;

    @Mock
    private OutboxEventService outboxEventService;

    @InjectMocks
    private LedgerServiceImpl ledgerService;

    @Test
    void createTransaction_ShouldFail_WhenAmountIsNegative() {
        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey("key-123")
                .setFromAccountId(UUID.randomUUID().toString())
                .setToAccountId(UUID.randomUUID().toString())
                .setAmount("-100.00")
                .setCurrency("HKD")
                .build();

        ledgerService.createTransaction(request, responseObserver);

        verify(responseObserver).onError(any(StatusRuntimeException.class));
        verify(accountRepository, never()).save(any());
    }
    
    @Test
    void createTransaction_ShouldFail_WhenIdempotencyKeyExists() {
        String key = "duplicate-key";
        when(transactionRepository.existsByIdempotencyKey(key)).thenReturn(true);

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey(key)
                .build();

        ledgerService.createTransaction(request, responseObserver);

        verify(responseObserver).onError(any(StatusRuntimeException.class));
    }

    // ========== Currency Mismatch Tests ==========

    @Test
    @DisplayName("createTransaction - Should fail when fromAccount currency mismatch")
    void createTransaction_ShouldFail_WhenFromAccountCurrencyMismatch() {
        // Given: fromAccount is HKD but request is USD
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();

        Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "1000.00");
        Account toAccount = TestDataBuilder.createUsdAccountWithId("user-2", "500.00");

        when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey("test-key")
                .setFromAccountId(fromAccountId.toString())
                .setToAccountId(toAccountId.toString())
                .setAmount("100.00")
                .setCurrency("USD")  // Mismatch with fromAccount HKD
                .setDescription("Test transaction")
                .build();

        // When
        ledgerService.createTransaction(request, responseObserver);

        // Then: Should return INVALID_ARGUMENT
        ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(exceptionCaptor.capture());

        StatusRuntimeException exception = exceptionCaptor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(exception.getStatus().getDescription()).contains("Currency mismatch");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTransaction - Should fail when toAccount currency mismatch")
    void createTransaction_ShouldFail_WhenToAccountCurrencyMismatch() {
        // Given: toAccount is EUR but request is HKD
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();

        Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "1000.00");
        Account toAccount = TestDataBuilder.createEurAccountWithId("user-2", "500.00");

        when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey("test-key")
                .setFromAccountId(fromAccountId.toString())
                .setToAccountId(toAccountId.toString())
                .setAmount("100.00")
                .setCurrency("HKD")  // Mismatch with toAccount EUR
                .setDescription("Test transaction")
                .build();

        // When
        ledgerService.createTransaction(request, responseObserver);

        // Then: Should return INVALID_ARGUMENT
        ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(exceptionCaptor.capture());

        StatusRuntimeException exception = exceptionCaptor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
        assertThat(exception.getStatus().getDescription()).contains("Currency mismatch");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTransaction - Should fail when accounts have different currencies")
    void createTransaction_ShouldFail_WhenAccountsHaveDifferentCurrencies() {
        // Given: fromAccount HKD, toAccount USD, request EUR - all different
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();

        Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "1000.00");
        Account toAccount = TestDataBuilder.createUsdAccountWithId("user-2", "500.00");

        when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey("test-key")
                .setFromAccountId(fromAccountId.toString())
                .setToAccountId(toAccountId.toString())
                .setAmount("100.00")
                .setCurrency("EUR")  // Different from both accounts
                .setDescription("Test transaction")
                .build();

        // When
        ledgerService.createTransaction(request, responseObserver);

        // Then: Should return INVALID_ARGUMENT
        ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(exceptionCaptor.capture());

        StatusRuntimeException exception = exceptionCaptor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);

        verify(accountRepository, never()).save(any());
        verify(transactionRepository, never()).save(any());
    }

    // ========== Insufficient Balance Edge Cases ==========

    @Test
    @DisplayName("createTransaction - Should fail when balance one cent short")
    void createTransaction_ShouldFail_WhenBalanceOneCentShort() {
        // Given: Balance 99.99, trying to transfer 100.00 (short by 0.01)
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();

        Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "99.99");
        Account toAccount = TestDataBuilder.createHkdAccountWithId("user-2", "500.00");

        when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey("test-key")
                .setFromAccountId(fromAccountId.toString())
                .setToAccountId(toAccountId.toString())
                .setAmount("100.00")
                .setCurrency("HKD")
                .setDescription("Test transaction")
                .build();

        // When
        ledgerService.createTransaction(request, responseObserver);

        // Then: Should return FAILED_PRECONDITION
        ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(exceptionCaptor.capture());

        StatusRuntimeException exception = exceptionCaptor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(exception.getStatus().getDescription()).contains("Insufficient funds");

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTransaction - Should fail when balance is zero")
    void createTransaction_ShouldFail_WhenBalanceIsZero() {
        // Given: Balance 0.00, trying to transfer 0.01
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();

        Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "0.00");
        Account toAccount = TestDataBuilder.createHkdAccountWithId("user-2", "500.00");

        when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey("test-key")
                .setFromAccountId(fromAccountId.toString())
                .setToAccountId(toAccountId.toString())
                .setAmount("0.01")
                .setCurrency("HKD")
                .setDescription("Test transaction")
                .build();

        // When
        ledgerService.createTransaction(request, responseObserver);

        // Then: Should return FAILED_PRECONDITION
        ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(exceptionCaptor.capture());

        StatusRuntimeException exception = exceptionCaptor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(exception.getStatus().getDescription()).contains("Insufficient funds");

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTransaction - Should fail when balance slightly less than amount")
    void createTransaction_ShouldFail_WhenBalanceSlightlyLessThanAmount() {
        // Given: Balance 100.00, trying to transfer 100.01 (short by 0.01)
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();

        Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "100.00");
        Account toAccount = TestDataBuilder.createHkdAccountWithId("user-2", "500.00");

        when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey("test-key")
                .setFromAccountId(fromAccountId.toString())
                .setToAccountId(toAccountId.toString())
                .setAmount("100.01")
                .setCurrency("HKD")
                .setDescription("Test transaction")
                .build();

        // When
        ledgerService.createTransaction(request, responseObserver);

        // Then: Should return FAILED_PRECONDITION
        ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(exceptionCaptor.capture());

        StatusRuntimeException exception = exceptionCaptor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(exception.getStatus().getDescription()).contains("Insufficient funds");

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTransaction - Should succeed when balance exactly covers amount")
    void createTransaction_ShouldSucceed_WhenBalanceExactlyCoversAmount() {
        // Given: Balance 100.00, transfer exactly 100.00 (edge case - should succeed)
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();

        Account fromAccount = TestDataBuilder.anAccount()
                .id(fromAccountId)
                .userId("user-1")
                .balance("100.00")
                .currency("HKD")
                .build();
        Account toAccount = TestDataBuilder.anAccount()
                .id(toAccountId)
                .userId("user-2")
                .balance("500.00")
                .currency("HKD")
                .build();

        when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            // Mock JPA behavior: set ID after save
            try {
                java.lang.reflect.Field idField = Transaction.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(tx, UUID.randomUUID());
            } catch (Exception e) {
                // Ignore reflection errors
            }
            return tx;
        });

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey("test-key")
                .setFromAccountId(fromAccountId.toString())
                .setToAccountId(toAccountId.toString())
                .setAmount("100.00")
                .setCurrency("HKD")
                .setDescription("Test transaction")
                .build();

        // When
        ledgerService.createTransaction(request, responseObserver);

        // Then: Should succeed
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());
        // Verify outbox event was created instead of direct Kafka send
        verify(outboxEventService, times(1)).createOutboxEvent(
            anyString(),  // aggregateId (transaction ID)
            eq("TRANSACTION"),
            eq("TRANSACTION_CREATED"),
            any(TransactionCreatedEvent.class),
            eq("transaction-events")
        );

        // Verify both accounts were saved with correct balances
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(accountCaptor.capture());

        List<Account> savedAccounts = accountCaptor.getAllValues();
        assertThat(savedAccounts).hasSize(2);

        // Find fromAccount and toAccount in saved accounts
        Account savedFromAccount = savedAccounts.stream()
                .filter(acc -> acc.getId().equals(fromAccountId))
                .findFirst()
                .orElseThrow();
        Account savedToAccount = savedAccounts.stream()
                .filter(acc -> acc.getId().equals(toAccountId))
                .findFirst()
                .orElseThrow();

        // Verify fromAccount balance decreased to 0.00 (100.00 - 100.00)
        assertThat(savedFromAccount.getBalance()).isEqualByComparingTo(new BigDecimal("0.00"));
        // Verify toAccount balance increased to 600.00 (500.00 + 100.00)
        assertThat(savedToAccount.getBalance()).isEqualByComparingTo(new BigDecimal("600.00"));
    }

    @Test
    @DisplayName("createTransaction - Should fail when large balance still insufficient")
    void createTransaction_ShouldFail_WhenLargeBalanceStillInsufficient() {
        // Given: Balance 9999.99, trying to transfer 10000.00 (short by 0.01)
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();

        Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "9999.99");
        Account toAccount = TestDataBuilder.createHkdAccountWithId("user-2", "500.00");

        when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey("test-key")
                .setFromAccountId(fromAccountId.toString())
                .setToAccountId(toAccountId.toString())
                .setAmount("10000.00")
                .setCurrency("HKD")
                .setDescription("Test transaction")
                .build();

        // When
        ledgerService.createTransaction(request, responseObserver);

        // Then: Should return FAILED_PRECONDITION
        ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
        verify(responseObserver).onError(exceptionCaptor.capture());

        StatusRuntimeException exception = exceptionCaptor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
        assertThat(exception.getStatus().getDescription()).contains("Insufficient funds");

        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTransaction - Should succeed when balance more than sufficient")
    void createTransaction_ShouldSucceed_WhenBalanceMoreThanSufficient() {
        // Given: Balance 1000.00, transfer 100.00 (plenty of funds)
        UUID fromAccountId = UUID.randomUUID();
        UUID toAccountId = UUID.randomUUID();

        Account fromAccount = TestDataBuilder.anAccount()
                .id(fromAccountId)
                .userId("user-1")
                .balance("1000.00")
                .currency("HKD")
                .build();
        Account toAccount = TestDataBuilder.anAccount()
                .id(toAccountId)
                .userId("user-2")
                .balance("500.00")
                .currency("HKD")
                .build();

        when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
        when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
        when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            Transaction tx = invocation.getArgument(0);
            // Mock JPA behavior: set ID after save
            try {
                java.lang.reflect.Field idField = Transaction.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(tx, UUID.randomUUID());
            } catch (Exception e) {
                // Ignore reflection errors
            }
            return tx;
        });

        CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                .setIdempotencyKey("test-key")
                .setFromAccountId(fromAccountId.toString())
                .setToAccountId(toAccountId.toString())
                .setAmount("100.00")
                .setCurrency("HKD")
                .setDescription("Test transaction")
                .build();

        // When
        ledgerService.createTransaction(request, responseObserver);

        // Then: Should succeed
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());
        // Verify outbox event was created instead of direct Kafka send
        verify(outboxEventService, times(1)).createOutboxEvent(
            anyString(),  // aggregateId (transaction ID)
            eq("TRANSACTION"),
            eq("TRANSACTION_CREATED"),
            any(TransactionCreatedEvent.class),
            eq("transaction-events")
        );

        // Verify both accounts were saved with correct balances
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(accountCaptor.capture());

        List<Account> savedAccounts = accountCaptor.getAllValues();
        assertThat(savedAccounts).hasSize(2);

        // Find fromAccount and toAccount in saved accounts
        Account savedFromAccount = savedAccounts.stream()
                .filter(acc -> acc.getId().equals(fromAccountId))
                .findFirst()
                .orElseThrow();
        Account savedToAccount = savedAccounts.stream()
                .filter(acc -> acc.getId().equals(toAccountId))
                .findFirst()
                .orElseThrow();

        // Verify fromAccount balance decreased to 900.00 (1000.00 - 100.00)
        assertThat(savedFromAccount.getBalance()).isEqualByComparingTo(new BigDecimal("900.00"));
        // Verify toAccount balance increased to 600.00 (500.00 + 100.00)
        assertThat(savedToAccount.getBalance()).isEqualByComparingTo(new BigDecimal("600.00"));
    }

    // ========== CreateAccount Tests ==========

    @Nested
    @DisplayName("CreateAccount Tests")
    class CreateAccountTests {

        @Mock
        private StreamObserver<AccountResponse> accountResponseObserver;

        @Test
        @DisplayName("createAccount - Should succeed with valid data and zero balance")
        void createAccount_ShouldSucceed_WithValidDataAndZeroBalance() {
            // Given
            CreateAccountRequest request = CreateAccountRequest.newBuilder()
                    .setUserId("user-123")
                    .setCurrency("HKD")
                    .build();

            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
                Account acc = invocation.getArgument(0);
                try {
                    java.lang.reflect.Field idField = Account.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(acc, UUID.randomUUID());
                } catch (Exception e) {
                    // Ignore
                }
                return acc;
            });

            // When
            ledgerService.createAccount(request, accountResponseObserver);

            // Then
            verify(accountResponseObserver).onCompleted();
            verify(accountResponseObserver, never()).onError(any());

            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());

            Account savedAccount = accountCaptor.getValue();
            assertThat(savedAccount.getUserId()).isEqualTo("user-123");
            assertThat(savedAccount.getCurrency()).isEqualTo("HKD");
            assertThat(savedAccount.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("createAccount - Should succeed with empty initial balance (defaults to 0)")
        void createAccount_ShouldSucceed_WithEmptyInitialBalance() {
            // Given
            CreateAccountRequest request = CreateAccountRequest.newBuilder()
                    .setUserId("user-123")
                    .setCurrency("USD")
                    .setInitialBalance("")
                    .build();

            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
                Account acc = invocation.getArgument(0);
                try {
                    java.lang.reflect.Field idField = Account.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(acc, UUID.randomUUID());
                } catch (Exception e) {
                    // Ignore
                }
                return acc;
            });

            // When
            ledgerService.createAccount(request, accountResponseObserver);

            // Then
            verify(accountResponseObserver).onCompleted();
            verify(transactionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("createAccount - Should succeed with positive initial balance and create Genesis transaction")
        void createAccount_ShouldSucceed_WithPositiveInitialBalance() {
            // Given
            UUID equityAccountId = UUID.randomUUID();
            Account equityAccount = TestDataBuilder.anAccount()
                    .id(equityAccountId)
                    .userId(DataInitializer.SYSTEM_USER_ID)
                    .currency("HKD")
                    .balance("1000000.00")
                    .build();

            CreateAccountRequest request = CreateAccountRequest.newBuilder()
                    .setUserId("user-123")
                    .setCurrency("HKD")
                    .setInitialBalance("500.00")
                    .build();

            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
                Account acc = invocation.getArgument(0);
                try {
                    java.lang.reflect.Field idField = Account.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    if (acc.getId() == null) {
                        idField.set(acc, UUID.randomUUID());
                    }
                } catch (Exception e) {
                    // Ignore
                }
                return acc;
            });

            when(accountRepository.findByUserIdAndCurrency(DataInitializer.SYSTEM_USER_ID, "HKD"))
                    .thenReturn(Optional.of(equityAccount));

            when(transactionRepository.saveAndFlush(any())).thenAnswer(invocation -> {
                Transaction tx = invocation.getArgument(0);
                try {
                    java.lang.reflect.Field idField = Transaction.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(tx, UUID.randomUUID());
                } catch (Exception e) {
                    // Ignore
                }
                return tx;
            });

            // When
            ledgerService.createAccount(request, accountResponseObserver);

            // Then
            verify(accountResponseObserver).onCompleted();
            verify(accountResponseObserver, never()).onError(any());
            verify(transactionRepository).saveAndFlush(any(Transaction.class));

            // Verify response contains correct balance
            ArgumentCaptor<AccountResponse> responseCaptor = ArgumentCaptor.forClass(AccountResponse.class);
            verify(accountResponseObserver).onNext(responseCaptor.capture());

            AccountResponse response = responseCaptor.getValue();
            assertThat(response.getBalance()).isEqualTo("500.00");
            assertThat(response.getCurrency()).isEqualTo("HKD");
        }

        @Test
        @DisplayName("createAccount - Should fail with missing userId")
        void createAccount_ShouldFail_WithMissingUserId() {
            // Given
            CreateAccountRequest request = CreateAccountRequest.newBuilder()
                    .setCurrency("HKD")
                    .build();

            // When
            ledgerService.createAccount(request, accountResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(accountResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("User ID is required");

            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("createAccount - Should fail with invalid currency (wrong length)")
        void createAccount_ShouldFail_WithInvalidCurrency() {
            // Given
            CreateAccountRequest request = CreateAccountRequest.newBuilder()
                    .setUserId("user-123")
                    .setCurrency("HKDD")  // 4 characters instead of 3
                    .build();

            // When
            ledgerService.createAccount(request, accountResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(accountResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Currency must be exactly 3 characters");

            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("createAccount - Should fail with negative initial balance")
        void createAccount_ShouldFail_WithNegativeBalance() {
            // Given
            CreateAccountRequest request = CreateAccountRequest.newBuilder()
                    .setUserId("user-123")
                    .setCurrency("HKD")
                    .setInitialBalance("-100.00")
                    .build();

            // When
            ledgerService.createAccount(request, accountResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(accountResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Initial balance cannot be negative");

            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("createAccount - Should fail with invalid balance format")
        void createAccount_ShouldFail_WithInvalidBalanceFormat() {
            // Given
            CreateAccountRequest request = CreateAccountRequest.newBuilder()
                    .setUserId("user-123")
                    .setCurrency("HKD")
                    .setInitialBalance("not-a-number")
                    .build();

            // When
            ledgerService.createAccount(request, accountResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(accountResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Invalid initial balance format");

            verify(accountRepository, never()).save(any());
        }

        @Test
        @DisplayName("createAccount - Should fail when equity account not found for genesis transaction")
        void createAccount_ShouldFail_WhenEquityAccountNotFound() {
            // Given
            CreateAccountRequest request = CreateAccountRequest.newBuilder()
                    .setUserId("user-123")
                    .setCurrency("JPY")  // Currency without equity account
                    .setInitialBalance("1000.00")
                    .build();

            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
                Account acc = invocation.getArgument(0);
                try {
                    java.lang.reflect.Field idField = Account.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(acc, UUID.randomUUID());
                } catch (Exception e) {
                    // Ignore
                }
                return acc;
            });

            when(accountRepository.findByUserIdAndCurrency(DataInitializer.SYSTEM_USER_ID, "JPY"))
                    .thenReturn(Optional.empty());

            // When
            ledgerService.createAccount(request, accountResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(accountResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        }

        @Test
        @DisplayName("createAccount - Should normalize currency to uppercase")
        void createAccount_ShouldNormalizeCurrencyToUppercase() {
            // Given
            CreateAccountRequest request = CreateAccountRequest.newBuilder()
                    .setUserId("user-123")
                    .setCurrency("hkd")  // lowercase
                    .build();

            when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
                Account acc = invocation.getArgument(0);
                try {
                    java.lang.reflect.Field idField = Account.class.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(acc, UUID.randomUUID());
                } catch (Exception e) {
                    // Ignore
                }
                return acc;
            });

            // When
            ledgerService.createAccount(request, accountResponseObserver);

            // Then
            verify(accountResponseObserver).onCompleted();

            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountRepository).save(accountCaptor.capture());

            Account savedAccount = accountCaptor.getValue();
            assertThat(savedAccount.getCurrency()).isEqualTo("HKD");  // Should be uppercase
        }
    }

    // ========== ListAccounts Tests ==========

    @Nested
    @DisplayName("ListAccounts Tests")
    class ListAccountsTests {

        @Mock
        private StreamObserver<ListAccountsResponse> listAccountsResponseObserver;

        @Test
        @DisplayName("listAccounts - Should return paginated results")
        void listAccounts_ShouldReturnPaginatedResults() {
            // Given
            String userId = "user-123";
            Account account1 = TestDataBuilder.createHkdAccountWithId(userId, "1000.00");
            Account account2 = TestDataBuilder.createUsdAccountWithId(userId, "500.00");

            Page<Account> accountPage = new PageImpl<>(List.of(account1, account2));

            ListAccountsRequest request = ListAccountsRequest.newBuilder()
                    .setUserId(userId)
                    .setPage(0)
                    .setPageSize(10)
                    .build();

            when(accountRepository.findAllByUserId(eq(userId), any(Pageable.class)))
                    .thenReturn(accountPage);

            // When
            ledgerService.listAccounts(request, listAccountsResponseObserver);

            // Then
            verify(listAccountsResponseObserver).onCompleted();
            verify(listAccountsResponseObserver, never()).onError(any());

            ArgumentCaptor<ListAccountsResponse> responseCaptor = ArgumentCaptor.forClass(ListAccountsResponse.class);
            verify(listAccountsResponseObserver).onNext(responseCaptor.capture());

            ListAccountsResponse response = responseCaptor.getValue();
            assertThat(response.getAccountsCount()).isEqualTo(2);
            assertThat(response.getTotalCount()).isEqualTo(2);
            assertThat(response.getPage()).isEqualTo(0);
            assertThat(response.getPageSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("listAccounts - Should return empty list when no accounts")
        void listAccounts_ShouldReturnEmptyList_WhenNoAccounts() {
            // Given
            String userId = "user-without-accounts";
            Page<Account> emptyPage = new PageImpl<>(Collections.emptyList());

            ListAccountsRequest request = ListAccountsRequest.newBuilder()
                    .setUserId(userId)
                    .setPage(0)
                    .setPageSize(20)
                    .build();

            when(accountRepository.findAllByUserId(eq(userId), any(Pageable.class)))
                    .thenReturn(emptyPage);

            // When
            ledgerService.listAccounts(request, listAccountsResponseObserver);

            // Then
            verify(listAccountsResponseObserver).onCompleted();

            ArgumentCaptor<ListAccountsResponse> responseCaptor = ArgumentCaptor.forClass(ListAccountsResponse.class);
            verify(listAccountsResponseObserver).onNext(responseCaptor.capture());

            ListAccountsResponse response = responseCaptor.getValue();
            assertThat(response.getAccountsCount()).isZero();
            assertThat(response.getTotalCount()).isZero();
        }

        @Test
        @DisplayName("listAccounts - Should use default pagination when not specified")
        void listAccounts_ShouldUseDefaultPagination_WhenNotSpecified() {
            // Given
            String userId = "user-123";
            Page<Account> emptyPage = new PageImpl<>(Collections.emptyList());

            ListAccountsRequest request = ListAccountsRequest.newBuilder()
                    .setUserId(userId)
                    .build();  // No page or pageSize specified

            when(accountRepository.findAllByUserId(eq(userId), any(Pageable.class)))
                    .thenReturn(emptyPage);

            // When
            ledgerService.listAccounts(request, listAccountsResponseObserver);

            // Then
            verify(listAccountsResponseObserver).onCompleted();

            ArgumentCaptor<ListAccountsResponse> responseCaptor = ArgumentCaptor.forClass(ListAccountsResponse.class);
            verify(listAccountsResponseObserver).onNext(responseCaptor.capture());

            ListAccountsResponse response = responseCaptor.getValue();
            assertThat(response.getPageSize()).isEqualTo(20);  // Default page size
        }

        @Test
        @DisplayName("listAccounts - Should fail with missing userId")
        void listAccounts_ShouldFail_WithMissingUserId() {
            // Given
            ListAccountsRequest request = ListAccountsRequest.newBuilder()
                    .setPage(0)
                    .setPageSize(10)
                    .build();  // No userId

            // When
            ledgerService.listAccounts(request, listAccountsResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(listAccountsResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("User ID is required");

            verify(accountRepository, never()).findAllByUserId(any(), any());
        }

        @Test
        @DisplayName("listAccounts - Should cap page size when too large")
        void listAccounts_ShouldCapPageSize_WhenTooLarge() {
            // Given
            String userId = "user-123";
            Page<Account> emptyPage = new PageImpl<>(Collections.emptyList());

            ListAccountsRequest request = ListAccountsRequest.newBuilder()
                    .setUserId(userId)
                    .setPage(0)
                    .setPageSize(500)  // Exceeds max of 100
                    .build();

            when(accountRepository.findAllByUserId(eq(userId), any(Pageable.class)))
                    .thenReturn(emptyPage);

            // When
            ledgerService.listAccounts(request, listAccountsResponseObserver);

            // Then
            verify(listAccountsResponseObserver).onCompleted();

            ArgumentCaptor<ListAccountsResponse> responseCaptor = ArgumentCaptor.forClass(ListAccountsResponse.class);
            verify(listAccountsResponseObserver).onNext(responseCaptor.capture());

            ListAccountsResponse response = responseCaptor.getValue();
            assertThat(response.getPageSize()).isEqualTo(100);  // Capped to max
        }
    }
}