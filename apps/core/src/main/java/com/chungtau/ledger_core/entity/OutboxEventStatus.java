package com.chungtau.ledger_core.entity;

public enum OutboxEventStatus {
    PENDING,     // Ready to publish
    PROCESSING,  // Currently being processed by a publisher instance
    PUBLISHED,   // Successfully published to Kafka
    FAILED       // Exceeded max retries, requires manual intervention
}
