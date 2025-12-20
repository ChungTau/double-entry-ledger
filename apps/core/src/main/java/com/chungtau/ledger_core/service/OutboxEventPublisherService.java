package com.chungtau.ledger_core.service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.chungtau.ledger_core.entity.OutboxEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisherService {

    private final OutboxEventService outboxEventService; // Inject Service for DB operations
    private final KafkaTemplate<String, Object> kafkaTemplate;

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
        // Send JSON string directly (already in Protobuf JSON format)
        // Consumer can deserialize using JsonFormat.parser() if needed
        // 使用 aggregateId 作為 Kafka key 確保同一交易的事件順序
        var sendResult = kafkaTemplate.send(
                event.getTopic(),
                event.getAggregateId(),
                event.getPayload());

        if (sendResult == null) {
            throw new IllegalStateException("KafkaTemplate.send() returned null - Kafka may not be configured properly");
        }

        // Block until send completes (within scheduler thread)
        sendResult.get(10, TimeUnit.SECONDS); // 10s timeout

        log.debug("Published event to Kafka: eventId={}, topic={}",
                event.getId(), event.getTopic());
    }
}
