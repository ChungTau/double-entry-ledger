package com.chungtau.ledger_core.unit.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.chungtau.ledger.grpc.v1.CreateTransactionRequest;
import com.chungtau.ledger.grpc.v1.TransactionResponse;
import com.chungtau.ledger_core.repository.AccountRepository;
import com.chungtau.ledger_core.repository.TransactionRepository;
import com.chungtau.ledger_core.service.LedgerServiceImpl;

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
}