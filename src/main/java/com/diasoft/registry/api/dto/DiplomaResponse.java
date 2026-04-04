package com.diasoft.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record DiplomaResponse(
        UUID id,
        UUID universityId,
        String universityCode,
        UUID studentId,
        String studentExternalId,
        String ownerName,
        String ownerNameMask,
        String diplomaNumber,
        String programName,
        Integer graduationYear,
        String recordHash,
        String status,
        String verificationToken,
        Instant revokedAt,
        String revokeReason,
        Instant createdAt,
        Instant updatedAt
) {}
