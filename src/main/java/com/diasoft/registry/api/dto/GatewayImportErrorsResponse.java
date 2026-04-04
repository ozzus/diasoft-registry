package com.diasoft.registry.api.dto;

import java.util.List;

public record GatewayImportErrorsResponse(
        List<GatewayImportRowErrorResponse> errors
) {}
