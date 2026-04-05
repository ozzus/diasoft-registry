package com.diasoft.registry.service;

import java.time.Instant;
import java.util.Map;

public record ObjectUploadTarget(
        String method,
        String uploadUrl,
        Map<String, String> headers,
        Instant expiresAt
) {}
