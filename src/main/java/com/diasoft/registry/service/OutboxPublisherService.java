package com.diasoft.registry.service;

import com.diasoft.registry.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxPublisherService {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final JdbcClient jdbcClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final AppProperties properties;
    private final Counter publishSuccessCounter;
    private final Counter publishFailureCounter;
    private final DistributionSummary publishedBatchSize;
    private final AtomicInteger backlogGauge = new AtomicInteger();

    public OutboxPublisherService(
            JdbcClient jdbcClient,
            KafkaTemplate<String, String> kafkaTemplate,
            Clock clock,
            AppProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.jdbcClient = jdbcClient;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.properties = properties;
        this.publishSuccessCounter = Counter.builder("diasoft.registry.outbox.publish.success").register(meterRegistry);
        this.publishFailureCounter = Counter.builder("diasoft.registry.outbox.publish.failure").register(meterRegistry);
        this.publishedBatchSize = DistributionSummary.builder("diasoft.registry.outbox.batch.size").register(meterRegistry);
        meterRegistry.gauge("diasoft.registry.outbox.backlog", backlogGauge);
    }

    @Transactional
    public int publishBatch(int batchSize) {
        refreshBacklogGauge();

        List<OutboxRecord> records = jdbcClient.sql("""
                select id, aggregate_id, event_type, payload
                from outbox_events
                where published = false
                order by created_at asc
                limit :batchSize
                for update skip locked
                """)
                .param("batchSize", batchSize)
                .query((rs, rowNum) -> new OutboxRecord(
                        rs.getObject("id", UUID.class),
                        rs.getString("aggregate_id"),
                        rs.getString("event_type"),
                        rs.getString("payload")
                ))
                .list();

        Instant now = Instant.now(clock);
        for (OutboxRecord record : records) {
            publishRecordWithRetry(record);
            jdbcClient.sql("""
                    update outbox_events
                    set published = true, published_at = :publishedAt
                    where id = :id
                    """)
                    .param("publishedAt", JdbcTime.timestamp(now))
                    .param("id", record.id())
                    .update();
            publishSuccessCounter.increment();
        }

        if (!records.isEmpty()) {
            publishedBatchSize.record(records.size());
        }
        refreshBacklogGauge();
        return records.size();
    }

    private void publishRecordWithRetry(OutboxRecord record) {
        int retryAttempts = properties.outbox().retryAttempts();
        Exception lastError = null;
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                kafkaTemplate.send(resolveTopic(record.eventType()), record.aggregateId(), record.payload()).get();
                return;
            } catch (Exception ex) {
                lastError = ex;
                if (attempt >= retryAttempts) {
                    publishFailureCounter.increment();
                    throw new IllegalStateException("failed to publish outbox event " + record.id(), ex);
                }
                Duration backoff = retryBackoff(attempt);
                log.warn(
                        "failed to publish outbox event, retrying: id={}, eventType={}, attempt={}, backoff={}",
                        record.id(),
                        record.eventType(),
                        attempt,
                        backoff,
                        ex
                );
                sleep(backoff);
            }
        }

        publishFailureCounter.increment();
        throw new IllegalStateException("failed to publish outbox event " + record.id(), lastError);
    }

    private void refreshBacklogGauge() {
        Integer backlog = jdbcClient.sql("select count(*) from outbox_events where published = false")
                .query(Integer.class)
                .single();
        backlogGauge.set(backlog == null ? 0 : backlog);
    }

    private Duration retryBackoff(int attempt) {
        Duration base = properties.outbox().retryBackoff();
        Duration max = properties.outbox().retryMaxBackoff();
        Duration current = base;
        for (int index = 1; index < attempt; index++) {
            current = current.multipliedBy(2);
            if (current.compareTo(max) > 0) {
                return max;
            }
        }
        return current.compareTo(max) > 0 ? max : current;
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(duration.toMillis(), 0));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("outbox publish retry interrupted", ex);
        }
    }

    private String resolveTopic(String eventType) {
        if (eventType.startsWith("diploma.")) {
            return "diploma.lifecycle.v1";
        }
        if (eventType.startsWith("sharelink.")) {
            return "sharelink.lifecycle.v1";
        }
        return "import.lifecycle.v1";
    }

    private record OutboxRecord(UUID id, String aggregateId, String eventType, String payload) {}
}
