package com.diasoft.registry.api.dto;

import java.util.List;

public record RegistryDiplomaListResponse(
        List<DiplomaResponse> items,
        long total
) {}
