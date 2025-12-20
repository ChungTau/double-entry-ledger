package com.chungtau.ledger_core.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chungtau.ledger_core.entity.OutboxEvent;
import com.chungtau.ledger_core.entity.OutboxEventStatus;
import com.chungtau.ledger_core.event.TransactionCreatedEvent;
import com.chungtau.ledger_core.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisherService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize;

    @Value("${outbox.publisher.retry.initial-interval-ms:1000}")
    private long initialRetryIntervalMs;

    @Value("${outbox.publisher.retry.multiplier:2.0}")
    private double retryMultiplier;

    @Value("${outbox.publisher.retry.jitter-ms:1000}")
    private long jitterMs;

    private final Random random = new Random();

    /**
     * Polls and publishes pending events from the outbox.
     * Scheduled execution controlled by application.yaml.
     */
    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        // 1. 提取並標記為 PROCESSING (短 transaction)
        List<OutboxEvent> events = fetchAndLockEvents(batchSize);

        if (events.isEmpty()) {
            return;
        }

        log.info("Publishing {} pending outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                // 2. 發送 Kafka (無 transaction)
                publishToKafka(event);

                // 3. 標記為 PUBLISHED (新 transaction)
                markAsPublished(event);
            } catch (Exception e) {
                // 4. 處理失敗 - 重新排隊或標記失敗 (新 transaction)
                handlePublishFailure(event, e);
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxEvent> fetchAndLockEvents(int batchSize) {
        Instant now = Instant.now();
        // SELECT FOR UPDATE SKIP LOCKED
        List<OutboxEvent> events = outboxEventRepository.findPendingEventsForPublishing(now, batchSize);

        if (events.isEmpty()) {
            return Collections.emptyList();
        }

        // 立即標記為 PROCESSING 並 commit
        List<UUID> ids = events.stream().map(OutboxEvent::getId).toList();
        outboxEventRepository.markAsProcessing(ids);
        outboxEventRepository.flush();

        return events;
    }

    private void publishToKafka(OutboxEvent event) throws Exception {
        // Deserialize payload back to Object for Kafka serializer
        Object payload = deserializePayload(event.getPayload(), event.getType());

        // Publish to Kafka synchronously within transaction
        // 使用 aggregateId 作為 Kafka key 確保同一交易的事件順序
        var sendResult = kafkaTemplate.send(
                event.getTopic(),
                event.getAggregateId(),
                payload);

        // Block until send completes (within scheduler thread)
        sendResult.get(10, TimeUnit.SECONDS); // 10s timeout

        log.debug("Published event to Kafka: eventId={}, topic={}",
                event.getId(), event.getTopic());
    }

    private Object deserializePayload(String jsonPayload, String eventType) {
        try {
            // Map event type to payload class
            Class<?> payloadClass = getPayloadClass(eventType);
            return objectMapper.readValue(jsonPayload, payloadClass);
        } catch (Exception e) {
            log.error("Failed to deserialize payload for eventType={}", eventType, e);
            throw new RuntimeException("Deserialization failed", e);
        }
    }

    private Class<?> getPayloadClass(String eventType) {
        // Event type registry
        return switch (eventType) {
            case "TRANSACTION_CREATED" -> TransactionCreatedEvent.class;
            // Add more event types here as needed
            default -> throw new IllegalArgumentException(
                    "Unknown event type: " + eventType);
        };
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsPublished(OutboxEvent event) {
        event.setStatus(OutboxEventStatus.PUBLISHED);
        event.setPublishedAt(Instant.now());
        outboxEventRepository.save(event);
        log.debug("Marked event as published: eventId={}", event.getId());
    }

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
            event.setStatus(OutboxEventStatus.PENDING); // 重新排隊

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
