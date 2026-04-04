package com.diasoft.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ImportJobResponse(
        UUID id,
        UUID universityId,
        String objectKey,
        String status,
        Integer totalRows,
        int processedRows,
        int failedRows,
        Instant createdAt,
        Instant updatedAt
) {}
