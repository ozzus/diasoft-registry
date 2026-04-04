package com.diasoft.registry.api.dto;

import java.util.UUID;

public record GatewayImportAcceptedResponse(
        UUID jobId,
        String status
) {}
