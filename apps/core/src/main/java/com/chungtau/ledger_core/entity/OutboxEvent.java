package com.chungtau.ledger_core.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "outbox_events", indexes = {
    @Index(name = "idx_status_created", columnList = "status, created_at"),
    @Index(name = "idx_next_retry", columnList = "next_retry_at"),
    @Index(name = "idx_aggregate", columnList = "aggregate_type, aggregate_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;   // e.g., Transaction ID

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType; // e.g., "TRANSACTION"

    @Column(name = "type", nullable = false)
    private String type;          // e.g., "TRANSACTION_CREATED"

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT") // Postgres JSONB or TEXT
    private String payload;       // JSON string of the event

    @Column(name = "topic", nullable = false)
    private String topic;         // Kafka Topic

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private OutboxEventStatus status = OutboxEventStatus.PENDING;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private Integer maxRetries = 5;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "processing_at")
    private Instant processingAt;  // Timestamp when marked as PROCESSING (for timeout detection)

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}