package com.chungtau.ledger_core.service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.chungtau.ledger_core.entity.OutboxEvent;
import com.chungtau.ledger_core.event.TransactionCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisherService {

    private final OutboxEventService outboxEventService; // Inject Service for DB operations
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${outbox.publisher.batch-size:50}")
    private int batchSize;

    /**
     * Polls and publishes pending events from the outbox.
     * Scheduled execution controlled by application.yaml.
     */
    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval-ms:1000}")
    public void publishPendingEvents() {
        // 1. Fetch and lock events (via Service Proxy - transaction works correctly)
        List<OutboxEvent> events = outboxEventService.fetchAndLockEvents(batchSize);

        if (events.isEmpty()) {
            return;
        }

        log.info("Publishing {} pending outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                // 2. Publish to Kafka (no transaction)
                publishToKafka(event);

                // 3. Mark as PUBLISHED (via Service Proxy - new transaction)
                outboxEventService.markAsPublished(event);
            } catch (Exception e) {
                // 4. Handle failure (via Service Proxy - new transaction)
                outboxEventService.handlePublishFailure(event, e);
            }
        }
    }

    private void publishToKafka(OutboxEvent event) throws Exception {
        // Deserialize payload back to Object for Kafka serializer
        Object payload = deserializePayload(event.getPayload(), event.getType());

        // Publish to Kafka synchronously
        // 使用 aggregateId 作為 Kafka key 確保同一交易的事件順序
        var sendResult = kafkaTemplate.send(
                event.getTopic(),
                event.getAggregateId(),
                payload);

        if (sendResult == null) {
            throw new IllegalStateException("KafkaTemplate.send() returned null - Kafka may not be configured properly");
        }

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
}
