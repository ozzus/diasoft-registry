package com.diasoft.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record UniversityResponse(
        UUID id,
        String code,
        String name,
        Instant createdAt
) {}
