package com.diasoft.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record DiplomaResponse(
        UUID id,
        UUID universityId,
        String universityCode,
        UUID studentId,
        String studentExternalId,
        String ownerNameMask,
        String diplomaNumber,
        String programName,
        String status,
        String verificationToken,
        Instant createdAt,
        Instant updatedAt
) {}
