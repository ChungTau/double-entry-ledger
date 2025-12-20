package com.chungtau.ledger_core.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.chungtau.ledger_core.entity.OutboxEvent;
import com.chungtau.ledger_core.entity.OutboxEventStatus;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    // Poll pending events for batch processing using FOR UPDATE SKIP LOCKED to prevent duplicate processing across instances
    // Note: Native query SELECT * automatically maps to OutboxEvent entity
    @Query(value = """
        SELECT * FROM outbox_events e
        WHERE e.status = 'PENDING'
        AND (e.next_retry_at IS NULL OR e.next_retry_at <= :now)
        ORDER BY e.created_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEvent> findPendingEventsForPublishing(
        @Param("now") Instant now,
        @Param("batchSize") int batchSize
    );

    // Batch update status to PROCESSING (used in fetchAndLockEvents)
    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = 'PROCESSING', e.processingAt = CURRENT_TIMESTAMP WHERE e.id IN :ids")
    void markAsProcessing(@Param("ids") List<UUID> ids);

    // Count pending events for monitoring (includes both PENDING and PROCESSING)
    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.status IN ('PENDING', 'PROCESSING')")
    long countPendingEvents();

    // Query failed events for alerting
    List<OutboxEvent> findByStatus(OutboxEventStatus status);

    // Optional: cleanup old published events
    @Modifying
    @Query("""
        DELETE FROM OutboxEvent e
        WHERE e.status = 'PUBLISHED'
        AND e.publishedAt < :cutoffDate
        """)
    int deleteOldPublishedEvents(@Param("cutoffDate") Instant cutoffDate);
}