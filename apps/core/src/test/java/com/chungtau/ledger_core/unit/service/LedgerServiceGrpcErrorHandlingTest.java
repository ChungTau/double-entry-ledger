package com.chungtau.ledger_core.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

import com.chungtau.ledger.grpc.v1.BalanceResponse;
import com.chungtau.ledger.grpc.v1.CreateTransactionRequest;
import com.chungtau.ledger.grpc.v1.GetBalanceRequest;
import com.chungtau.ledger.grpc.v1.TransactionResponse;
import com.chungtau.ledger_core.entity.Account;
import com.chungtau.ledger_core.fixtures.TestDataBuilder;
import com.chungtau.ledger_core.repository.AccountRepository;
import com.chungtau.ledger_core.repository.TransactionRepository;
import com.chungtau.ledger_core.service.LedgerServiceImpl;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

/**
 * Comprehensive gRPC error handling tests for LedgerServiceImpl.
 *
 * Test Coverage:
 * - ALREADY_EXISTS: Idempotency key duplication
 * - INVALID_ARGUMENT: Invalid inputs (empty IDs, invalid formats, negative amounts, currency mismatch)
 * - NOT_FOUND: Account not found scenarios
 * - FAILED_PRECONDITION: Insufficient funds
 * - INTERNAL: Unexpected exceptions
 * - getBalance method: All error paths
 *
 * Corresponds to error handling in:
 * - LedgerServiceImpl.java:153-167 (createTransaction exception handling)
 * - LedgerServiceImpl.java:200-213 (getBalance exception handling)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LedgerService gRPC Error Handling Tests")
class LedgerServiceGrpcErrorHandlingTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private StreamObserver<TransactionResponse> transactionResponseObserver;

    @Mock
    private StreamObserver<BalanceResponse> balanceResponseObserver;

    @InjectMocks
    private LedgerServiceImpl ledgerService;

    // ========== ALREADY_EXISTS Tests ==========

    @Nested
    @DisplayName("ALREADY_EXISTS Status Tests")
    class AlreadyExistsTests {

        @Test
        @DisplayName("Should return ALREADY_EXISTS when idempotency key exists")
        void shouldReturnAlreadyExists_WhenIdempotencyKeyExists() {
            // Given
            String duplicateKey = "duplicate-key-123";
            when(transactionRepository.existsByIdempotencyKey(duplicateKey)).thenReturn(true);

            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey(duplicateKey)
                    .setFromAccountId(UUID.randomUUID().toString())
                    .setToAccountId(UUID.randomUUID().toString())
                    .setAmount("100.00")
                    .setCurrency("HKD")
                    .setDescription("Test")
                    .build();

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
            assertThat(exception.getStatus().getDescription()).contains("Idempotency Key already exists");

            verify(accountRepository, never()).findById(any());
        }
    }

    // ========== INVALID_ARGUMENT Tests ==========

    @Nested
    @DisplayName("INVALID_ARGUMENT Status Tests")
    class InvalidArgumentTests {

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when fromAccountId is empty")
        void shouldReturnInvalidArgument_WhenFromAccountIdIsEmpty() {
            // Given
            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey("test-key")
                    .setFromAccountId("")  // Empty
                    .setToAccountId(UUID.randomUUID().toString())
                    .setAmount("100.00")
                    .setCurrency("HKD")
                    .build();

            when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Account IDs are required");
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when toAccountId is empty")
        void shouldReturnInvalidArgument_WhenToAccountIdIsEmpty() {
            // Given
            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey("test-key")
                    .setFromAccountId(UUID.randomUUID().toString())
                    .setToAccountId("")  // Empty
                    .setAmount("100.00")
                    .setCurrency("HKD")
                    .build();

            when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Account IDs are required");
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when fromAccountId format is invalid")
        void shouldReturnInvalidArgument_WhenFromAccountIdFormatInvalid() {
            // Given
            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey("test-key")
                    .setFromAccountId("invalid-uuid-format")  // Invalid UUID
                    .setToAccountId(UUID.randomUUID().toString())
                    .setAmount("100.00")
                    .setCurrency("HKD")
                    .build();

            when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Invalid source account ID format");
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when toAccountId format is invalid")
        void shouldReturnInvalidArgument_WhenToAccountIdFormatInvalid() {
            // Given
            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey("test-key")
                    .setFromAccountId(UUID.randomUUID().toString())
                    .setToAccountId("not-a-valid-uuid")  // Invalid UUID
                    .setAmount("100.00")
                    .setCurrency("HKD")
                    .build();

            when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Invalid destination account ID format");
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when amount format is invalid")
        void shouldReturnInvalidArgument_WhenAmountFormatInvalid() {
            // Given
            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey("test-key")
                    .setFromAccountId(UUID.randomUUID().toString())
                    .setToAccountId(UUID.randomUUID().toString())
                    .setAmount("not-a-number")  // Invalid amount
                    .setCurrency("HKD")
                    .build();

            when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Invalid amount format");
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when amount is negative")
        void shouldReturnInvalidArgument_WhenAmountIsNegative() {
            // Given
            UUID fromAccountId = UUID.randomUUID();
            UUID toAccountId = UUID.randomUUID();

            Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "1000.00");
            Account toAccount = TestDataBuilder.createHkdAccountWithId("user-2", "500.00");

            when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
            when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
            when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));

            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey("test-key")
                    .setFromAccountId(fromAccountId.toString())
                    .setToAccountId(toAccountId.toString())
                    .setAmount("-100.00")  // Negative amount
                    .setCurrency("HKD")
                    .build();

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("must be positive");
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when amount is zero")
        void shouldReturnInvalidArgument_WhenAmountIsZero() {
            // Given
            UUID fromAccountId = UUID.randomUUID();
            UUID toAccountId = UUID.randomUUID();

            Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "1000.00");
            Account toAccount = TestDataBuilder.createHkdAccountWithId("user-2", "500.00");

            when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
            when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
            when(accountRepository.findById(toAccountId)).thenReturn(Optional.of(toAccount));

            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey("test-key")
                    .setFromAccountId(fromAccountId.toString())
                    .setToAccountId(toAccountId.toString())
                    .setAmount("0.00")  // Zero amount
                    .setCurrency("HKD")
                    .build();

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("must be positive");
        }

        @Test
        @DisplayName("Should return INVALID_ARGUMENT when currency mismatches")
        void shouldReturnInvalidArgument_WhenCurrencyMismatches() {
            // Given
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
                    .setCurrency("EUR")  // Mismatch
                    .build();

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Currency mismatch");
        }
    }

    // ========== NOT_FOUND Tests ==========

    @Nested
    @DisplayName("NOT_FOUND Status Tests")
    class NotFoundTests {

        @Test
        @DisplayName("Should return NOT_FOUND when fromAccount does not exist")
        void shouldReturnNotFound_WhenFromAccountDoesNotExist() {
            // Given
            UUID fromAccountId = UUID.randomUUID();
            UUID toAccountId = UUID.randomUUID();

            when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
            // Mock both accounts to handle lock ordering - one will be empty
            // Use lenient() because only one will be called due to lock ordering
            lenient().when(accountRepository.findById(fromAccountId)).thenReturn(Optional.empty());  // Not found
            lenient().when(accountRepository.findById(toAccountId)).thenReturn(Optional.empty());     // Also empty to avoid null issues

            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey("test-key")
                    .setFromAccountId(fromAccountId.toString())
                    .setToAccountId(toAccountId.toString())
                    .setAmount("100.00")
                    .setCurrency("HKD")
                    .build();

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
            assertThat(exception.getStatus().getDescription()).contains("Account not found");
        }

        @Test
        @DisplayName("Should return NOT_FOUND when toAccount does not exist")
        void shouldReturnNotFound_WhenToAccountDoesNotExist() {
            // Given
            Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "1000.00");
            UUID fromAccountId = fromAccount.getId();  // Use the actual ID from the account
            UUID toAccountId = UUID.randomUUID();

            // Note: Due to lock ordering in LedgerServiceImpl, the actual account retrieval order
            // depends on UUID comparison. We need to handle both orders.
            when(transactionRepository.existsByIdempotencyKey(any())).thenReturn(false);
            // Set both findById calls to handle lock ordering
            when(accountRepository.findById(fromAccountId)).thenReturn(Optional.of(fromAccount));
            when(accountRepository.findById(toAccountId)).thenReturn(Optional.empty());  // Not found

            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey("test-key")
                    .setFromAccountId(fromAccountId.toString())
                    .setToAccountId(toAccountId.toString())
                    .setAmount("100.00")
                    .setCurrency("HKD")
                    .build();

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
            assertThat(exception.getStatus().getDescription()).contains("Account not found");
        }

        @Test
        @DisplayName("getBalance - Should return NOT_FOUND when account does not exist")
        void getBalance_ShouldReturnNotFound_WhenAccountDoesNotExist() {
            // Given
            UUID accountId = UUID.randomUUID();
            when(accountRepository.findById(accountId)).thenReturn(Optional.empty());

            GetBalanceRequest request = GetBalanceRequest.newBuilder()
                    .setAccountId(accountId.toString())
                    .build();

            // When
            ledgerService.getBalance(request, balanceResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(balanceResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.NOT_FOUND);
            assertThat(exception.getStatus().getDescription()).contains("Account not found");
        }
    }

    // ========== FAILED_PRECONDITION Tests ==========

    @Nested
    @DisplayName("FAILED_PRECONDITION Status Tests")
    class FailedPreconditionTests {

        @Test
        @DisplayName("Should return FAILED_PRECONDITION when insufficient funds")
        void shouldReturnFailedPrecondition_WhenInsufficientFunds() {
            // Given
            UUID fromAccountId = UUID.randomUUID();
            UUID toAccountId = UUID.randomUUID();

            Account fromAccount = TestDataBuilder.createHkdAccountWithId("user-1", "50.00");  // Insufficient
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
                    .build();

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.FAILED_PRECONDITION);
            assertThat(exception.getStatus().getDescription()).contains("Insufficient funds");
        }
    }

    // ========== INTERNAL Tests ==========

    @Nested
    @DisplayName("INTERNAL Status Tests")
    class InternalTests {

        @Test
        @DisplayName("Should return INTERNAL when unexpected exception occurs in createTransaction")
        void shouldReturnInternal_WhenUnexpectedExceptionOccurs() {
            // Given
            when(transactionRepository.existsByIdempotencyKey(any()))
                    .thenThrow(new RuntimeException("Database connection failed"));

            CreateTransactionRequest request = CreateTransactionRequest.newBuilder()
                    .setIdempotencyKey("test-key")
                    .setFromAccountId(UUID.randomUUID().toString())
                    .setToAccountId(UUID.randomUUID().toString())
                    .setAmount("100.00")
                    .setCurrency("HKD")
                    .build();

            // When
            ledgerService.createTransaction(request, transactionResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(transactionResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(exception.getStatus().getDescription()).contains("Internal processing error");
        }

        @Test
        @DisplayName("getBalance - Should return INTERNAL when unexpected exception occurs")
        void getBalance_ShouldReturnInternal_WhenUnexpectedExceptionOccurs() {
            // Given
            UUID accountId = UUID.randomUUID();
            when(accountRepository.findById(accountId))
                    .thenThrow(new RuntimeException("Database error"));

            GetBalanceRequest request = GetBalanceRequest.newBuilder()
                    .setAccountId(accountId.toString())
                    .build();

            // When
            ledgerService.getBalance(request, balanceResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(balanceResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
            assertThat(exception.getStatus().getDescription()).contains("Failed to fetch balance");
        }
    }

    // ========== getBalance Method Tests ==========

    @Nested
    @DisplayName("getBalance Method Tests")
    class GetBalanceTests {

        @Test
        @DisplayName("getBalance - Should return INVALID_ARGUMENT when accountId is empty")
        void getBalance_ShouldReturnInvalidArgument_WhenAccountIdIsEmpty() {
            // Given
            GetBalanceRequest request = GetBalanceRequest.newBuilder()
                    .setAccountId("")  // Empty
                    .build();

            // When
            ledgerService.getBalance(request, balanceResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(balanceResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Account ID is required");
        }

        @Test
        @DisplayName("getBalance - Should return INVALID_ARGUMENT when accountId format is invalid")
        void getBalance_ShouldReturnInvalidArgument_WhenAccountIdFormatInvalid() {
            // Given
            GetBalanceRequest request = GetBalanceRequest.newBuilder()
                    .setAccountId("invalid-uuid-format")
                    .build();

            // When
            ledgerService.getBalance(request, balanceResponseObserver);

            // Then
            ArgumentCaptor<StatusRuntimeException> exceptionCaptor = ArgumentCaptor.forClass(StatusRuntimeException.class);
            verify(balanceResponseObserver).onError(exceptionCaptor.capture());

            StatusRuntimeException exception = exceptionCaptor.getValue();
            assertThat(exception.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
            assertThat(exception.getStatus().getDescription()).contains("Invalid account ID format");
        }

        @Test
        @DisplayName("getBalance - Should return balance when account exists")
        void getBalance_ShouldReturnBalance_WhenAccountExists() {
            // Given
            UUID accountId = UUID.randomUUID();
            Account account = TestDataBuilder.createHkdAccountWithId("user-1", "1234.5678");

            // Set ID and version using reflection
            try {
                java.lang.reflect.Field idField = Account.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(account, accountId);
                account.setVersion(5L);
            } catch (Exception e) {
                // Ignore reflection errors
            }

            when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

            GetBalanceRequest request = GetBalanceRequest.newBuilder()
                    .setAccountId(accountId.toString())
                    .build();

            // When
            ledgerService.getBalance(request, balanceResponseObserver);

            // Then
            ArgumentCaptor<BalanceResponse> responseCaptor = ArgumentCaptor.forClass(BalanceResponse.class);
            verify(balanceResponseObserver).onNext(responseCaptor.capture());
            verify(balanceResponseObserver).onCompleted();

            BalanceResponse response = responseCaptor.getValue();
            assertThat(response.getAccountId()).isEqualTo(accountId.toString());
            assertThat(response.getCurrency()).isEqualTo("HKD");
            assertThat(response.getBalance()).isEqualTo("1234.5678");
            assertThat(response.getVersion()).isEqualTo(5L);
        }
    }
}
