package com.chungtau.ledger_core.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.chungtau.ledger_core.entity.Transaction;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    //Used to prevents double-spending.
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    //Find transactions by external reference ID.
    List<Transaction> findByReferenceId(String referenceId);

    //Find transactions within a specific time range.
    List<Transaction> findByBookedAtBetween(Instant start, Instant end);
    
    //Checks if a transaction exists by its idempotency key.
    boolean existsByIdempotencyKey(String idempotencyKey);
}