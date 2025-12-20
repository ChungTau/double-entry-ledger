package com.chungtau.ledger_core.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.chungtau.ledger_core.entity.OutboxEventStatus;
import com.chungtau.ledger_core.repository.OutboxEventRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for exposing Outbox Event metrics to Prometheus/Micrometer.
 *
 * Metrics exposed:
 * - outbox.events.pending: Number of PENDING events (waiting to be published)
 * - outbox.events.processing: Number of PROCESSING events (currently being published)
 * - outbox.events.failed: Number of FAILED events (exceeded max retries)
 *
 * These metrics can be used for:
 * - Alerting: If pending count keeps growing, Kafka might be down
 * - Monitoring: Track outbox processing health
 * - Capacity Planning: Understand event throughput
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxMetricsService {

    private final OutboxEventRepository outboxEventRepository;
    private final MeterRegistry meterRegistry;

    /**
     * Periodically update outbox event metrics.
     * Runs every 10 seconds by default.
     */
    @Scheduled(fixedDelayString = "${outbox.metrics.update-interval-ms:10000}")
    public void updateMetrics() {
        try {
            long pendingCount = outboxEventRepository.countByStatus(OutboxEventStatus.PENDING);
            long processingCount = outboxEventRepository.countByStatus(OutboxEventStatus.PROCESSING);
            long failedCount = outboxEventRepository.countByStatus(OutboxEventStatus.FAILED);

            // Update gauges
            meterRegistry.gauge("outbox.events",
                java.util.List.of(Tag.of("status", "pending")),
                pendingCount);

            meterRegistry.gauge("outbox.events",
                java.util.List.of(Tag.of("status", "processing")),
                processingCount);

            meterRegistry.gauge("outbox.events",
                java.util.List.of(Tag.of("status", "failed")),
                failedCount);

            log.debug("Outbox metrics updated: pending={}, processing={}, failed={}",
                pendingCount, processingCount, failedCount);

        } catch (Exception e) {
            log.error("Failed to update outbox metrics", e);
        }
    }
}
