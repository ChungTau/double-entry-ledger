package com.chungtau.ledger_core.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chungtau.ledger_core.entity.OutboxEvent;
import com.chungtau.ledger_core.entity.OutboxEventStatus;
import com.chungtau.ledger_core.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventService {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    // Retry Configs
    @Value("${outbox.publisher.retry.initial-interval-ms:1000}")
    private long initialRetryIntervalMs;

    @Value("${outbox.publisher.retry.multiplier:2.0}")
    private double retryMultiplier;

    @Value("${outbox.publisher.retry.jitter-ms:1000}")
    private long jitterMs;

    private final Random random = new Random();

    /**
     * Creates an outbox event for a domain event.
     * Must be called within the same transaction as the business operation.
     *
     * @param aggregateId   The entity ID (e.g., transaction ID)
     * @param aggregateType The entity type (e.g., "TRANSACTION")
     * @param eventType     The event type (e.g., "TRANSACTION_CREATED")
     * @param payload       The event payload object (will be JSON serialized)
     * @param topic         The Kafka topic
     * @return The persisted OutboxEvent
     */
    public OutboxEvent createOutboxEvent(
            String aggregateId,
            String aggregateType,
            String eventType,
            Object payload,
            String topic) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);

            OutboxEvent event = OutboxEvent.builder()
                    .aggregateId(aggregateId)
                    .aggregateType(aggregateType)
                    .type(eventType)
                    .payload(jsonPayload)
                    .topic(topic)
                    .status(OutboxEventStatus.PENDING)
                    .retryCount(0)
                    .maxRetries(5)
                    .build();

            OutboxEvent saved = outboxEventRepository.save(event);
            log.debug("Created outbox event: id={}, type={}, aggregateId={}",
                    saved.getId(), eventType, aggregateId);

            return saved;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event payload for {}", eventType, e);
            throw new IllegalArgumentException("Invalid event payload", e);
        }
    }

    /**
     * Fetch and lock pending events for publishing.
     * Uses REQUIRES_NEW to ensure a short, independent transaction.
     * This method is called via Spring Proxy, so @Transactional works correctly.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> fetchAndLockEvents(int batchSize) {
        Instant now = Instant.now();
        // SELECT FOR UPDATE SKIP LOCKED
        List<OutboxEvent> events = outboxEventRepository.findPendingEventsForPublishing(now, batchSize);

        if (events.isEmpty()) {
            return Collections.emptyList();
        }

        // Mark as PROCESSING and commit immediately
        List<UUID> ids = events.stream().map(OutboxEvent::getId).toList();
        outboxEventRepository.markAsProcessing(ids);
        outboxEventRepository.flush(); // Ensure update is sent to DB

        return events;
    }

    /**
     * Mark an event as successfully published.
     * Uses REQUIRES_NEW to ensure a short, independent transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsPublished(OutboxEvent event) {
        event.setStatus(OutboxEventStatus.PUBLISHED);
        event.setPublishedAt(Instant.now());
        outboxEventRepository.save(event);
        log.debug("Marked event as published: eventId={}", event.getId());
    }

    /**
     * Handle event publishing failure with exponential backoff and retry logic.
     * Uses REQUIRES_NEW to ensure a short, independent transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handlePublishFailure(OutboxEvent event, Exception error) {
        event.setRetryCount(event.getRetryCount() + 1);
        event.setLastError(truncateError(error.getMessage()));

        if (event.getRetryCount() >= event.getMaxRetries()) {
            event.setStatus(OutboxEventStatus.FAILED);
            log.error("Event publishing permanently failed after {} retries: eventId={}",
                    event.getMaxRetries(), event.getId(), error);
        } else {
            // Calculate exponential backoff with jitter
            long delayMs = (long) (initialRetryIntervalMs *
                    Math.pow(retryMultiplier, event.getRetryCount() - 1));
            long jitter = random.nextLong(jitterMs + 1); // 0 to jitterMs
            event.setNextRetryAt(Instant.now().plusMillis(delayMs + jitter));
            event.setStatus(OutboxEventStatus.PENDING); // Re-queue for retry

            log.warn("Event publishing failed (retry {}/{}): eventId={}, nextRetry={}",
                    event.getRetryCount(), event.getMaxRetries(),
                    event.getId(), event.getNextRetryAt(), error);
        }

        outboxEventRepository.save(event);
    }

    private String truncateError(String error) {
        if (error == null)
            return null;
        return error.length() > 2000 ? error.substring(0, 2000) : error;
    }
}
