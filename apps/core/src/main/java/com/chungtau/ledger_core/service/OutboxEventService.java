package com.chungtau.ledger_core.service;

import org.springframework.stereotype.Service;

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
}
