package com.diasoft.registry.api.dto;

import java.util.List;

public record GatewayDiplomaListResponse(
        List<DiplomaResponse> items,
        long total
) {}
