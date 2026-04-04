package com.diasoft.registry.api.dto;

import java.time.Instant;
import java.util.UUID;

public record ShareLinkResponse(
        UUID id,
        UUID diplomaId,
        String shareToken,
        Instant expiresAt,
        Integer maxViews,
        int usedViews,
        String status,
        String shareUrl
) {}
