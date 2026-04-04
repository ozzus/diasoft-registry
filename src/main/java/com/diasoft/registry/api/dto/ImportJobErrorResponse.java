package com.diasoft.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ImportJobErrorResponse(
        UUID id,
        UUID importJobId,
        int rowNumber,
        String code,
        String message,
        Instant createdAt
) {}
