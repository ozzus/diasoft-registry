package com.diasoft.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ImportJobSummaryResponse(
        UUID jobId,
        String status,
        Integer rowsTotal,
        int rowsProcessed,
        int rowsFailed,
        int chunksTotal,
        int chunksCompleted,
        int fileCount,
        Instant createdAt,
        Instant updatedAt
) {}
