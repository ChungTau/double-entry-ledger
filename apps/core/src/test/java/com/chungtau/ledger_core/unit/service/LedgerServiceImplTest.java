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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.chungtau.ledger.grpc.v1.CreateTransactionRequest;
import com.chungtau.ledger.grpc.v1.TransactionResponse;
import com.chungtau.ledger.grpc.v1.TransactionCreatedEvent;
import com.chungtau.ledger_core.entity.Account;
import com.chungtau.ledger_core.entity.OutboxEvent;
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
}