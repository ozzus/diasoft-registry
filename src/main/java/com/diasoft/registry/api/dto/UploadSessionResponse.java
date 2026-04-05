package com.diasoft.registry.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UploadSessionResponse(
        UUID sessionId,
        long maxFileSizeBytes,
        int maxRowsPerFile,
        Instant expiresAt,
        List<UploadSessionTargetResponse> uploads
) {}
