package com.diasoft.registry.api.dto;

import java.time.Instant;

public record GatewayQrResponse(
        String verificationToken,
        String qrUrl,
        Instant expiresAt
) {}
