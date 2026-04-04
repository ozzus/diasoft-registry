package com.diasoft.registry.api.dto;

public record GatewayImportRowErrorResponse(
        int row,
        String message
) {}
