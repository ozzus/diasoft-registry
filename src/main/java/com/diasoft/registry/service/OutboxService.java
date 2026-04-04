package com.diasoft.registry.service;

import com.diasoft.registry.config.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AppProperties properties;

    public OutboxService(JdbcClient jdbcClient, ObjectMapper objectMapper, Clock clock, AppProperties properties) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
    }

    public void append(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload) {
        Instant now = Instant.now(clock);
        jdbcClient.sql("""
                insert into outbox_events (
                    id, aggregate_type, aggregate_id, event_type, event_version, payload, published, created_at
                ) values (
                    :id, :aggregateType, :aggregateId, :eventType, :eventVersion, cast(:payload as jsonb), false, :createdAt
                )
                """)
                .param("id", UUID.randomUUID())
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId.toString())
                .param("eventType", eventType)
                .param("eventVersion", "v1")
                .param("payload", toEnvelopeJson(aggregateType, aggregateId, eventType, payload, now))
                .param("createdAt", now)
                .update();
    }

    private String toEnvelopeJson(String aggregateType, UUID aggregateId, String eventType, Map<String, Object> payload, Instant occurredAt) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "event_id", UUID.randomUUID().toString(),
                    "event_type", eventType,
                    "event_version", "v1",
                    "occurred_at", occurredAt,
                    "producer", properties.outbox().producer(),
                    "aggregate_type", aggregateType,
                    "aggregate_id", aggregateId.toString(),
                    "payload", payload
            ));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode outbox payload", ex);
        }
    }
}
