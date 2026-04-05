package com.diasoft.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ImportJobResponse(
        UUID id,
        UUID universityId,
        String objectKey,
        UUID uploadSessionId,
        String status,
        String sourceFormat,
        int fileCount,
        Integer totalRows,
        int processedRows,
        int failedRows,
        int totalChunks,
        int completedChunks,
        Instant createdAt,
        Instant updatedAt
) {}
