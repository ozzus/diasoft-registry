package com.diasoft.registry.api.dto;

import jakarta.validation.constraints.NotNull;

public record GatewayShareLinkRequest(
        @NotNull Integer ttlSeconds
) {}
