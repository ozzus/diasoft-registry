package com.diasoft.registry.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(JdbcClient jdbcClient, ObjectMapper objectMapper, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void log(String actorId, String action, String entityType, String entityId, Map<String, Object> payload) {
        String payloadJson = toJson(payload);
        Instant now = Instant.now(clock);
        jdbcClient.sql("""
                insert into audit_logs (id, actor_id, action, entity_type, entity_id, payload, created_at)
                values (:id, :actorId, :action, :entityType, :entityId, cast(:payload as jsonb), :createdAt)
                """)
                .param("id", UUID.randomUUID())
                .param("actorId", actorId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("payload", payloadJson)
                .param("createdAt", now)
                .update();
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to encode audit payload", ex);
        }
    }
}
