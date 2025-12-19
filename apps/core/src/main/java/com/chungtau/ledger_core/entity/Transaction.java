package com.chungtau.ledger_core.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Immutable
@Table(name = "transactions", indexes = {
    @Index(name = "idx_reference_id", columnList = "reference_id")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "entries")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    //Prevents double-spending 
    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "reference_id")
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @CreationTimestamp
    @Column(name = "booked_at", nullable = false, updatable = false)
    private Instant bookedAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TransactionEntry> entries;

    public static Transaction createTransfer(String idempotencyKey, String referenceId,
        Account fromAccount, Account toAccount, BigDecimal amount) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey cannot be null or blank");
        }
        if (fromAccount == null) {
            throw new IllegalArgumentException("fromAccount cannot be null");
        }
        if (toAccount == null) {
            throw new IllegalArgumentException("toAccount cannot be null");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        Transaction tx = Transaction.builder()
        .idempotencyKey(idempotencyKey)
        .referenceId(referenceId)
        .status(TransactionStatus.POSTED)
        .build();

        List<TransactionEntry> entryList = new ArrayList<>();

        // Debit
        entryList.add(TransactionEntry.builder()
        .transaction(tx)
        .account(fromAccount)
        .amount(amount)
        .direction(EntryDirection.DEBIT)
        .build());

        // Credit
        entryList.add(TransactionEntry.builder()
        .transaction(tx)
        .account(toAccount)
        .amount(amount)
        .direction(EntryDirection.CREDIT)
        .build());

        tx.entries = entryList;

        return tx;
    }
}