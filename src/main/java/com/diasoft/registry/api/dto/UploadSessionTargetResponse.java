package com.diasoft.registry.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record UploadSessionTargetResponse(
        UUID fileId,
        String fileName,
        String objectKey,
        String method,
        String uploadUrl,
        Map<String, String> headers,
        Instant expiresAt
) {}
